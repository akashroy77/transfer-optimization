package org.onedatashare.transfer.model.core;

import com.sun.xml.internal.ws.policy.sourcemodel.ModelNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.onedatashare.transfer.model.TransferDetails;
import org.onedatashare.transfer.model.TransferDetailsRepository;
import org.onedatashare.transfer.model.drain.Drain;
import org.onedatashare.transfer.model.request.TransferOptions;
import org.onedatashare.transfer.model.tap.Tap;
import org.onedatashare.transfer.model.util.Progress;
import org.onedatashare.transfer.model.util.Throughput;
import org.onedatashare.transfer.model.util.Time;
import org.onedatashare.transfer.model.util.TransferInfo;
import org.onedatashare.transfer.module.Resource;
import org.onedatashare.transfer.service.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


@NoArgsConstructor
@Data
public class Transfer<S extends Resource, D extends Resource> {
    public S source;
    public D destination;
    public List<IdMap> filesToTransfer;
    public TransferOptions options;
    public String sourceBaseUri;
    public String destinationBaseUri;
    private static HashMap<String,Long> timestamp=new HashMap<String, Long>();
    public AtomicInteger concurrency = new AtomicInteger(5);

    long totalTransferTime;

    //@Autowired
    //private TransferDetailsRepository repository;

    private ArrayList<Disposable> disposableArrayList = new ArrayList<>();
    private static final Logger logger = LoggerFactory.getLogger(Transfer.class);

    /** Periodically updated information about the ongoing transfer. */
    public final TransferInfo info = new TransferInfo();

    // Timer counts 0.0 for files with very small size
    protected Time timer;
    protected Progress progress = new Progress();
    protected Throughput throughput = new Throughput();

    public Transfer(S source, D destination){
        this.source = source;
        this.destination = destination;
    }

  public Flux start(int sliceSize){
        logger.info("Within transfer start");
        long totalStartTime = System.nanoTime();
        logger.info("Total Start Time:"+totalStartTime/1000000);
        //long endTime = startTime;
        return Flux.fromIterable(filesToTransfer)
                .doOnSubscribe(s -> {
                    logger.info("Transfer started....");
                    totalTransferTime = System.nanoTime();
                })
                //.parallel(4)
                //.runOn(Schedulers.elastic())
                .flatMap(file -> {
                    logger.info("Transferring " + file.getUri()+" "+Thread.currentThread().getName());
                    Tap tap;
                    try {
                        long startTime = System.nanoTime();
                        timestamp.put(file.getUri(),startTime);
                        logger.info("Start Time" + file.getUri() + "--" + Thread.currentThread().getName() + "--" + timestamp.get(file.getUri()));
                        tap = source.getTap(file, sourceBaseUri);
                    } catch (Exception e) {
                        logger.error("Unable to read from the tap - " + e.getMessage());
                        return Flux.empty();
                    }
                    Drain drain;
                    try {
                        drain = destination.getDrain(file, destinationBaseUri);
                    } catch (Exception e) {
                        logger.error("Unable to create a new file drain - " + e.getMessage());
                        return Flux.empty();
                    }
                    Drain finalDrain = drain;
                    return tap.openTap(sliceSize)
                            .doOnNext(slice -> {
//                                logger.info("slice recieved");
                                try {
                                    finalDrain.drain(slice);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            })
                            .doOnComplete(() -> {
                                try {
                                    finalDrain.finish();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }).doOnComplete(() ->{
                                long endTime = System.nanoTime();
                                long duration=endTime-timestamp.getOrDefault(file.getUri(),0l);
                                timestamp.put(file.getUri(),duration/1000000);
//                                logger.info("Done transferring Inner "+file.getUri()+" "+timestamp.get(file.getUri()));
                                })
                            .subscribeOn(Schedulers.elastic());
                })
             //   .sequential()
                .doOnComplete(() -> {
                    logger.info("Done transferring");
                    totalTransferTime = System.nanoTime() - totalTransferTime;
                    double totalTransferTimeDouble = totalTransferTime / 1000000000.0;
                    logger.info("Endpoint "+ totalTransferTimeDouble);
                    //TransferService.savetoMongo();
//                    //setTransferDetails();
//                    TransferDetails transferDetails = new TransferDetails("testfile", 2l);
//                    try {
//                        repository.save((transferDetails));
//                    } catch(Exception e)
//                    {
//                        logger.info("Exception in saving to mongodb" + e.getMessage());
//                        e.printStackTrace();
//                    }
                });
    }

//    public void setTransferDetails(){
//        logger.info("Inside setTransferDetails");
//        TransferDetails transferDetails = new TransferDetails("testfile", 2l);
//        //transferDetails.setFileName("testfile");
//        //transferDetails.setDuration(0l);
//        try {
//            repository.saveAll(Mono.just(transferDetails)).subscribe();
//        } catch(Exception e)
//        {
//            logger.info("Exception in saving to mongodb" + e.getMessage());
//            e.printStackTrace();
//        }
////        for(Map.Entry <String, Long> e : timestamp.entrySet())
////        {
////            transferDetails.setFileName(e.getKey());
////            transferDetails.setDuration(e.getValue());
////            logger.info(transferDetails.getFileName());
////            logger.info(Long.toString(transferDetails.getDuration()));
////            repository.saveAll(Flux.just(transferDetails)).subscribe();
////            logger.info("Saving response to mongodb");
////        }
//        //return;
//
//    }

    public TransferInfo addProgress(Slice slice) {
        long size = slice.length();
        progress.add(size);
        throughput.update(size);
        info.update(timer, progress, throughput);
        return info;
    }
}
