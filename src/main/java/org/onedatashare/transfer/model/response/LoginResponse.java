package org.onedatashare.transfer.model.response;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.onedatashare.transfer.model.core.User;

@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LoginResponse {
    private String email;
    private String token;
    private boolean saveOAuthTokens;
    private boolean isAdmin;
    private boolean compactViewEnabled;
    private long expiresIn;

    public static LoginResponse LoginResponseFromUser(User user, String token, long expiresIn){
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.token = token;
        loginResponse.email = user.getEmail();
        loginResponse.compactViewEnabled = user.isCompactViewEnabled();
        loginResponse.isAdmin = user.isAdmin();
        loginResponse.saveOAuthTokens = user.isSaveOAuthTokens();
        loginResponse.expiresIn = expiresIn;
        return loginResponse;
    }
}