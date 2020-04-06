package org.onedatashare.transfer.module.dropbox;

import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;
import org.onedatashare.transfer.model.core.Credential;
import org.onedatashare.transfer.model.core.Session;
import org.onedatashare.transfer.model.credential.OAuthCredential;
import org.onedatashare.transfer.model.error.AuthenticationRequired;
import org.onedatashare.transfer.model.useraction.IdMap;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;

import static org.onedatashare.transfer.model.core.ODSConstants.DROPBOX_CLIENT_IDENTIFIER;

public class DbxSession extends Session<DbxSession, DbxResource> {
  private DbxClientV2 client;

  protected DbxClientV2 getClient(){
    return client;
  }

  public DbxSession(URI uri, Credential cred) {
    super(uri, cred);
  }

  @Override
  public Mono<DbxResource> select(String path) {
    return Mono.just(new DbxResource(this, path));
  }

  @Override
  public Mono<DbxResource> select(String path, String id) {
    return Mono.just(new DbxResource(this, path));
  }

  @Override
  public Mono<DbxResource> select(String path, String id, ArrayList<IdMap> idMap) {
    return Mono.just(new DbxResource(this, path));
  }
  @Override
  public Mono<DbxSession> initialize() {
    return Mono.create(s -> {
      if(getCredential() instanceof OAuthCredential){
        OAuthCredential oauth = (OAuthCredential) getCredential();
        DbxRequestConfig config =
                DbxRequestConfig.newBuilder(DROPBOX_CLIENT_IDENTIFIER).build();
        client = new DbxClientV2(config, oauth.token);
        s.success(this);
      }
      else s.error(new AuthenticationRequired("oauth"));
    });
  }
}
