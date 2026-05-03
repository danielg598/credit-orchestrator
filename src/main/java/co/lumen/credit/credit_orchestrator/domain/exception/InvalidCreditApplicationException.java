package co.lumen.credit.credit_orchestrator.domain.exception;

public class InvalidCreditApplicationException extends RuntimeException{
    public InvalidCreditApplicationException(String message) {
        super(message);
    }
}
