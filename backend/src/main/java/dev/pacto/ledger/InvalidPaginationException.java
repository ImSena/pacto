package dev.pacto.ledger;

public class InvalidPaginationException
        extends IllegalArgumentException {

    public InvalidPaginationException(String message) {
        super(message);
    }
}