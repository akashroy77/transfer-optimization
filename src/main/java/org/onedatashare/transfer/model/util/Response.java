package org.onedatashare.transfer.model.util;


public class Response {
    public String response;
    public int status;
    public Response(String type, int statuscode){
        response = type;
        status = statuscode;
    }
}
