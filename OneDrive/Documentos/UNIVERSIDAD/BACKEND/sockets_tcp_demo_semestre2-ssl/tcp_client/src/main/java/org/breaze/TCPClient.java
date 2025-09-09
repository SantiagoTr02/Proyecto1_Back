package org.breaze;

import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

public class TCPClient {
    private String serverAddress;
    private int serverPort;
    private Socket clientSocket;
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;

    public TCPClient(String serverAddress, int serverPort){
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
    }

    public void connect() throws IOException {
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        this.clientSocket = sslSocketFactory.createSocket(serverAddress, serverPort);
        System.out.println("Connected to server: " + this.serverAddress + ":" + this.serverPort);
        this.dataInputStream = new DataInputStream(this.clientSocket.getInputStream());
        this.dataOutputStream = new DataOutputStream(this.clientSocket.getOutputStream());
    }
    public void sendMessage(String name, String lastName){
        String response = "Error";
        try{
            this.connect();
            String message = name+":"+lastName;
            System.out.println("Sending message: " + message);
            this.dataOutputStream.writeUTF(message);
            response = this.dataInputStream.readUTF();
            System.out.println("Received response: " + response);
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        }finally {
            this.closeConnection();
        }
    }

    public void closeConnection(){
        try{
            if(this.dataInputStream != null) this.dataInputStream.close();
            if(this.dataOutputStream != null) this.dataOutputStream.close();
            if(this.clientSocket != null) this.clientSocket.close();
        } catch (IOException e) {
            System.out.println("Error closing connection: " + e.getMessage());
        }
    }
}
