/**
 ##**************************************************************
 ##
 ## Copyright (C) 2018-2020, OneDataShare Team, 
 ## Department of Computer Science and Engineering,
 ## University at Buffalo, Buffalo, NY, 14260.
 ## 
 ## Licensed under the Apache License, Version 2.0 (the "License"); you
 ## may not use this file except in compliance with the License.  You may
 ## obtain a copy of the License at
 ## 
 ##    http://www.apache.org/licenses/LICENSE-2.0
 ## 
 ## Unless required by applicable law or agreed to in writing, software
 ## distributed under the License is distributed on an "AS IS" BASIS,
 ## WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ## See the License for the specific language governing permissions and
 ## limitations under the License.
 ##
 ##**************************************************************
 */


package org.oneDatashare.transfer.model.core;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.oneDatashare.transfer.model.util.Progress;
import org.oneDatashare.transfer.model.util.Throughput;
import org.oneDatashare.transfer.model.util.Time;
import org.oneDatashare.transfer.model.util.TransferInfo;
import org.oneDatashare.transfer.module.box.BoxResource;
import org.oneDatashare.transfer.module.gridftp.GridftpResource;
import org.oneDatashare.transfer.module.gridftp.GridftpSession;
import org.oneDatashare.transfer.module.http.HttpResource;
import org.oneDatashare.transfer.service.ODSLoggerService;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.TimeUnit;

@NoArgsConstructor
@Data
public class Transfer<S extends Resource, D extends Resource> {
    public S source;
    public D destination;

    /** Periodically updated information about the ongoing transfer. */
    public final TransferInfo info = new TransferInfo();

    // Timer counts 0.0 for files with very small size
    protected Time timer;
    protected Progress progress = new Progress();
    protected Throughput throughput = new Throughput();

    public Transfer(S source, D destination) {
        this.source = source;
        this.destination = destination;
    }

    public Flux<TransferInfo> start(Long sliceSize) {
        if (source instanceof GridftpResource && destination instanceof GridftpResource){
            return ((GridftpResource) source).transferTo(((GridftpResource) destination)).flatMapMany(result -> {
                String taskId = result.getTaskId();
                startTimer();
                info.setTotal(Long.MAX_VALUE);

                return Flux.generate(() -> info, (state, sink) -> {

                    ((GridftpSession)((GridftpResource) source).getSession()).client.getTaskDetail(taskId).map(detail -> {
                        long total = detail.getBytes_transferred();
                        addProgressSize((Long)total);
                        String status = detail.getStatus();
                        if("ACTIVE".equals(status)){
                            sink.next(info);
                        }else if("INACTIVE".equals(status)){
                            sink.next(info);
                        }else if("SUCCEEDED".equals(status)){
                            info.setTotal(total);
                            sink.next(info);
                            sink.complete();
                        }else if("FAILED".equals(status)){
                            sink.error(new Exception("Globus transfer failure"));
                        }
                        return info;
                    }).subscribe();
                    try {
                        TimeUnit.SECONDS.sleep(2);
                    }catch(InterruptedException e){}
                    return info;
                }).subscribeOn(Schedulers.elastic()).take(100).map(info -> (TransferInfo)info).doFinally(s -> done());
            });
        }else if (source instanceof GridftpResource || destination instanceof GridftpResource){
            return Flux.error(new Exception("Can not send from GridFTP to other protocols"));
        }
// HTTP is read only
        if(destination instanceof HttpResource)
            return Flux.error(new Exception("HTTP is read-only"));

        Stat tapStat = (Stat)source.getTransferStat().block();
        info.setTotal(tapStat.getSize());


        return Flux.fromIterable(tapStat.getFilesList())
                .doOnSubscribe(s -> startTimer())
                .flatMap(fileStat -> {
                    final Drain drain;
                    if( destination instanceof BoxResource){
                        drain =  ((BoxResource)destination).sink(fileStat, tapStat.isDir());
                    }
                    else if(tapStat.isDir())
                        drain = destination.sink(fileStat);
                    else {
                        drain = destination.sink();
                    }
                    return source.tap().tap(fileStat, sliceSize)
                            .subscribeOn(Schedulers.elastic())
                            .doOnNext(drain::drain)
                            .subscribeOn(Schedulers.elastic())
                            .map(this::addProgress)
                            .doOnComplete(drain::finish);
                }).doFinally(s -> done());
    }

    public void initialize() {
        Stat stat = (Stat) source.stat().block();
        info.setTotal(stat.getSize());
    }

    public void initializeUpload(int fileSize){
        info.setTotal(fileSize);
    }

    public void done() {
        timer.stop();
    }

    public void startTimer() {
        timer = new Time();
    }

    public TransferInfo addProgress(Slice slice) {
        long size = slice.length();
        progress.add(size);
        throughput.update(size);
        info.update(timer, progress, throughput);
        return info;
    }

    public TransferInfo addProgressSize(Long totalSize) {
        long size = totalSize - progress.total();
        progress.add(size);
        throughput.update(size);
        info.update(timer, progress, throughput);
        return info;
    }

    public Transfer<S, D> setSource(S source) {
        this.source = source;
        return this;
    }

    public Transfer<S, D> setDestination(D destination) {
        this.destination = destination;
        return this;
    }


    /**
     * This method was developed for debugging purposes.
     * This method ensures that the transfer is performed sequentially.
     * @param sliceSize
     * @return TransferInfo - returned purposely to satisfy return constraint
     */
    public Flux<TransferInfo> blockingStart(Long sliceSize) {

        if (source instanceof GridftpResource && destination instanceof GridftpResource){
            ((GridftpResource) source).transferTo(((GridftpResource) destination)).subscribe();
            return Flux.empty();
        }else if (source instanceof GridftpResource || destination instanceof GridftpResource){
            return Flux.error(new Exception("Can not send from GridFTP to other protocols"));
        }

        Stat tapStat = (Stat)source.getTransferStat().block();
        info.setTotal(tapStat.getSize());

        startTimer();
        for(Stat fileStat : tapStat.getFilesList()){
            final Drain drain;
            if(tapStat.isDir())
                drain = destination.sink(fileStat);
            else
                drain = destination.sink();
            source.tap().tap(fileStat, sliceSize)
                    .subscribeOn(Schedulers.elastic())
                    .doOnNext(drain::drain)
                    .subscribeOn(Schedulers.elastic())
                    .map(this::addProgress)
                    .blockLast();
            drain.finish();
            ODSLoggerService.logInfo(fileStat.getName() + " transferred");
        }
        done();
        return Flux.just(info);
    }
}
