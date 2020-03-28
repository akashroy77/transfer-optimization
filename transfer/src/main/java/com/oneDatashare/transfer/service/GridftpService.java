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



package com.oneDatashare.transfer.service;

import org.onedatashare.module.globusapi.Result;
import com.oneDatashare.transfer.model.core.ODSConstants;
import com.oneDatashare.transfer.model.core.Stat;
import com.oneDatashare.transfer.model.credential.GlobusWebClientCredential;
import com.oneDatashare.transfer.model.useraction.UserAction;
import com.oneDatashare.transfer.module.gridftp.GridftpResource;
import com.oneDatashare.transfer.module.gridftp.GridftpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.UnsupportedEncodingException;
import java.net.URI;

@Service
public class GridftpService {

    @Autowired
    private UserService userService;

    public Mono<Stat> list(String cookie, UserAction userAction) {
        return getResourceWithUserUserAction(cookie, userAction).flatMap(GridftpResource::stat);
    }

    public Mono<GridftpResource> getResourceWithUserUserAction(String cookie, UserAction userAction) {
        final String path = pathFromUri(userAction.getUri());
        return userService.getLoggedInUser(cookie)
            .flatMap(user -> userService.getGlobusClient(cookie).map(client -> new GlobusWebClientCredential(userAction.getCredential().getGlobusEndpoint(), client)))
            .map(credential -> new GridftpSession(URI.create(userAction.getUri()), credential))
            .flatMap(GridftpSession::initialize)
            .flatMap(GridftpSession -> GridftpSession.select(path));
    }

    public Mono<Result> delete(String cookie, UserAction userAction) {
        return getResourceWithUserUserAction(cookie, userAction)
                .flatMap(GridftpResource::deleteV2);
    }

    public Mono<Stat> mkdir(String cookie, UserAction userAction) {
        return getResourceWithUserUserAction(cookie, userAction)
                .flatMap(GridftpResource::mkdir)
                .flatMap(GridftpResource::stat);
    }

    public static String pathFromUri(String uri) {
        String path;
        if(uri.contains(ODSConstants.GRIDFTP_URI_SCHEME)){
            path = uri.substring(ODSConstants.GRIDFTP_URI_SCHEME.length());
        }
        else path = uri;
        try {
            path = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return path;
    }
}
