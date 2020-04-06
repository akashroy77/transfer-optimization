package org.onedatashare.transfer.module.http;

import org.onedatashare.transfer.model.core.Session;
import org.onedatashare.transfer.model.useraction.IdMap;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;

public class HttpSession extends Session<HttpSession, HttpResource> {
    public HttpSession(URI uri) {
        super(uri);
    }

    @Override
    /*
    * Ignoring path and idMap (Not used)
     */
    public Mono<HttpResource> select(String path, String id, ArrayList<IdMap> idMap) {
        return Mono.just(new HttpResource(this, getUri().toString()));
    }

    @Override
    public Mono<HttpSession> initialize() {
        return Mono.create(s-> s.success(this));
    }

    @Override
    public Mono<HttpResource> select(String path){
        return Mono.just(new HttpResource(this, getUri().toString()));
    }
}
