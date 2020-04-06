package org.onedatashare.transfer.service;

import org.apache.commons.lang.RandomStringUtils;
import org.onedatashare.transfer.model.core.*;
import org.onedatashare.transfer.model.error.*;
import org.onedatashare.transfer.model.response.LoginResponse;
import org.onedatashare.transfer.model.credential.OAuthCredential;
import org.onedatashare.transfer.model.useraction.UserActionCredential;
import org.onedatashare.transfer.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;

import static org.onedatashare.transfer.model.core.ODSConstants.TOKEN_TIMEOUT_IN_MINUTES;

/**
 * Service class for all operations related to users' information.
 */
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JWTUtil jwtUtil;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<User> createUser(User user) {
        user.setRegisterMoment(System.currentTimeMillis());
        return userRepository.insert(user);
    }

    public Mono<LoginResponse> login(String email, String password){
        return getUser(User.normalizeEmail(email))
                .filter(usr -> usr.getHash().equals(usr.hash(password)))
                .switchIfEmpty(Mono.error(new InvalidODSCredentialsException("Invalid username or password")))
                .map(user -> LoginResponse.LoginResponseFromUser(user, jwtUtil.generateToken(user), JWTUtil.getExpirationTime()))
                .doOnSuccess(userLogin -> saveLastActivity(email,System.currentTimeMillis()).subscribe());
    }

    public Mono<User> doesUserExists(String email) {
        User user = new User();
        return userRepository.findById(email)
                .switchIfEmpty(Mono.just(user))
                .onErrorResume(
                        throwable -> throwable instanceof Exception,
                        throwable -> Mono.just(user));
    }

    public Mono<Boolean> resetPassword(String email, String password, String passwordConfirm, String authToken){
        return getUser(email).flatMap(user-> {
            if(!password.equals(passwordConfirm)){
                return Mono.error(new Exception("Password is not confirmed."));
            }else if(user.getAuthToken() == null){
                return Mono.error(new Exception("Does not have Auth Token"));
            }else if(user.getAuthToken().equals(authToken)){
                user.setPassword(password);
                // Setting the verification code to null while resetting the password.
                // This will allow the user to use the same verification code multiple times with in 24 hrs.
                user.setCode(null);
                user.setAuthToken(null);
                user.setValidated(true);
                userRepository.save(user).subscribe();
                return Mono.just(true);
            }else{
                return Mono.error(new Exception("Wrong Token"));
            }
        });
    }

    public Mono<User> getUser(String email) {
         return userRepository.findById(email)
                 .switchIfEmpty(Mono.error(new Exception("No User found with Id: " + email)));
    }

    public Mono<User> getUserFromCookie(String email, String cookie) {
        return  getLoggedInUser(cookie).flatMap(user->{
            if(user != null && user.getEmail().equals(email)){
                return Mono.just(user);
            }
            return Mono.error(new Exception("No User found with Id: " + email));
        });
    }

    public Mono<User> saveUser(User user) {
        return userRepository.save(user);
    }

    public Mono<Void> updateViewPreference(String email, boolean isCompactViewEnabled){
        return getUser(email).map(user -> {
            user.setCompactViewEnabled(isCompactViewEnabled);
            return userRepository.save(user).subscribe();
        }).then();
    }

    public Mono<LinkedList<URI>> saveHistory(String uri, String cookie) {
        return getLoggedInUser(cookie).map(user -> {
            URI historyItem = URI.create(uri);
            if(!user.getHistory().contains(historyItem)) {
                user.getHistory().add(historyItem);
            }
            return user;
        })
                .flatMap(userRepository::save).map(User::getHistory);
    }

    public Mono<Boolean> userLoggedIn() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> auth.getPrincipal() != null);
    }

    public Mono<Boolean> validate(String email, String authToken){
        return getUser(email).flatMap(user-> {
            if(user.isValidated()){
                return Mono.error(new Exception("Already Validated"));
            }else if(user.getAuthToken() == null){
                return Mono.error(new Exception("Did not have Auth Token"));
            }else if(user.getAuthToken().equals(authToken)){
                user.setValidated(true);
                user.setAuthToken(null);
                userRepository.save(user).subscribe();
                return Mono.just(true);
            }else{
                return Mono.error(new Exception("Wrong Token"));
            }
        });
    }

    /**
     * @description verifycode will generation auth token if it is not already created.
     * Every auth token is available to use once.
     * If used auth token is set to null and reset next time user verify the code.
     *
     * @author Yifuyin
     * @param email email of the user of the operation
     * @param code code to verify
     * @return String Auth Token
     */

    public Mono<String> verifyCode(String email, String code){
        return getUser(email).flatMap(user-> {
            User.VerifyCode expectedCode = user.getCode();
            if(expectedCode == null){
                return Mono.error(new Exception("code not set"));
            }else if(expectedCode.expireDate.before(new Date())){
                return Mono.error(new Exception("code expired"));
            }else if(expectedCode.code.equals(code)){
                user.setAuthToken(code+User.salt(12));
                userRepository.save(user).subscribe();
                return Mono.just(user.getAuthToken());
            }else{
                return Mono.error(new Exception("Code not match"));
            }
        });
    }

    public Mono<Boolean> isRegisteredEmail(String email) {
        return userRepository.existsById(email);
    }

    /**
     * //TODO: remove this function
     * Modified the function to use the security context
     * Placeholder function that will be removed later
     * @return
     */
    public Mono<User> getLoggedInUser(String cookie) {
        return getLoggedInUser();
    }

    /**
     * Modified the function to use security context for logging in
     * @return User : The current logged in user
     */
    public Mono<User> getLoggedInUser() {
        return getLoggedInUserEmail()
                .flatMap(this::getUser);
    }


    /**
     * This function returns the email id of the user that has made the request.
     * This information is retrieved from security context set using JWT
     * @return email: Email id of the user making the request
     */
    public Mono<String> getLoggedInUserEmail(){
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (String) securityContext.getAuthentication().getPrincipal());
    }

    public Mono<UUID> saveCredential(OAuthCredential credential) {
        final UUID uuid = UUID.randomUUID();
        return getLoggedInUser()
                .map(user -> {
                    user.getCredentials().put(uuid, credential);
                    userRepository.save(user).subscribe();
                    return uuid;
                });
    }

    /**
     * Saves the OAuth Credentials in user collection when the user toggles the preference button.
     * @param credentials The list of Oauth Credentials
     * @return
     */
    public Mono<Void> saveUserCredentials(List<OAuthCredential> credentials) {
        return getLoggedInUser()
                .map(user -> {
                    for(OAuthCredential credential : credentials) {
                        final UUID uuid = UUID.randomUUID();
                        user.getCredentials().put(uuid, credential);
                    }
                    return user;
                })
                .flatMap(userRepository::save).then();
    }

    public Mono<Void> saveLastActivity(String email, Long lastActivity) {
        return getUser(email).doOnSuccess(user -> {
            user.setLastActivity(lastActivity);
            userRepository.save(user).subscribe();
        }).then();
    }

    public Mono<Long> getLastActivity(String cookie) {
        return getLoggedInUser(cookie).map(user -> user.getLastActivity());
    }


    public Mono<Void> deleteCredential(String cookie, String uuid) {
        return getLoggedInUser(cookie)
                .map(user -> {
                    if(user.getCredentials().remove(UUID.fromString(uuid))== null) {
                        return Mono.error(new NotFoundException());
                    }
                    return userRepository.save(user).subscribe();
                }).then();
    }

    public OAuthCredential updateCredential(String cookie, UserActionCredential userActionCredential, OAuthCredential credential) {
//Updating the access token for googledrive using refresh token or deleting credential if refresh token is expired.
        getLoggedInUser(cookie)
                .doOnSuccess(user -> {
                    Map<UUID,Credential> credsTemporary = user.getCredentials();
                    UUID uid = UUID.fromString(userActionCredential.getUuid());
                    OAuthCredential val = (OAuthCredential) credsTemporary.get(uid);
                    if(credential.refreshTokenExp){
                        credsTemporary.remove(uid);
                    }else if(val.refreshToken != null && val.refreshToken.equals(credential.refreshToken)){
                        credsTemporary.replace(uid, credential);
                    }
                    if(user.isSaveOAuthTokens()) {
                        user.setCredentials(credsTemporary);
                        userRepository.save(user).subscribe();
                    }
                }).subscribe();

        return credential;
    }

    public Mono<User> deleteBoxCredential(String cookie, UserActionCredential userActionCredential, OAuthCredential credential) {
//Updating the access token for googledrive using refresh token or deleting credential if refresh token is expired.
        return getLoggedInUser(cookie)
                .flatMap(user -> {
                    Map<UUID,Credential> credsTemporary = user.getCredentials();
                    UUID uid = UUID.fromString(userActionCredential.getUuid());
//OAuthCredential val = (OAuthCredential) credsTemporary.get(uid);
                    credsTemporary.remove(uid);
                    if(user.isSaveOAuthTokens()) {
                        user.setCredentials(credsTemporary);
                        return userRepository.save(user);
                    }else{
                        user.setCredentials(credsTemporary);
                        return null;
                    }
                });

//return credential;
    }

    public Mono<Void> deleteHistory(String cookie, String uri) {
        return getLoggedInUser(cookie)
                .map(user -> {
                    if(user.getHistory().remove(URI.create(uri))) {
                        return userRepository.save(user).subscribe();
                    }
                    return Mono.error(new NotFoundException());
                }).then();
    }

    public Mono<Void> updateSaveOAuth(String cookie, boolean saveOAuthCredentials){
        return getLoggedInUser(cookie).map(user -> {
            user.setSaveOAuthTokens(saveOAuthCredentials);
            // Remove the saved credentials
            if(!saveOAuthCredentials)
                user.setCredentials(new HashMap<>());
            return userRepository.save(user).subscribe();
        }).then();
    }

    public Mono<Boolean> isAdmin(String cookie){
        return getLoggedInUser(cookie).map(user ->user.isAdmin());
    }

    /**
     * Service method that retrieves all existing credentials linked to a user account.
     *
     * @return a map containing all the endpoint credentials linked to the user account as a Mono
     */
    public Mono<Map<UUID, Credential>> getCredentials() {
        return getLoggedInUser()
                .map(user -> {
                    removeIfExpired(user.getCredentials());
                    userRepository.save(user).subscribe();
                    return user.getCredentials();
                });
    }

    public Map<UUID, Credential> removeIfExpired(Map<UUID, Credential> creds){
        ArrayList<UUID> removingThese = new ArrayList<UUID>();
        for(Map.Entry<UUID, Credential> entry : creds.entrySet()){
            if(entry.getValue().type == Credential.CredentialType.GLOBUS &&
                    ((OAuthCredential)entry.getValue()).name.equals("GridFTP Client") &&
                    ((OAuthCredential)entry.getValue()).expiredTime != null &&
                    Calendar.getInstance().getTime().after(((OAuthCredential)entry.getValue()).expiredTime))
            {
                removingThese.add(entry.getKey());
            }
        }
        for(UUID id : removingThese){
            creds.remove(id);
        }
        return creds;
    }


    public Flux<UUID> getJobs(String cookie) {
        return getLoggedInUser(cookie).map(User::getJobs).flux().flatMap(Flux::fromIterable);
    }
}