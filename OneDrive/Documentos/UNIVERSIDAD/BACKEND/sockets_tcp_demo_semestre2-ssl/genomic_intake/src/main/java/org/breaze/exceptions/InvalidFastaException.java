package org.breaze.exceptions;

public class InvalidFastaException extends Exception {
    public InvalidFastaException(String message) { super(message); }
    public InvalidFastaException(String message, Throwable cause) { super(message, cause); }
}