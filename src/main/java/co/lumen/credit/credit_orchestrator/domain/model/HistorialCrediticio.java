package co.lumen.credit.credit_orchestrator.domain.model;

public enum HistorialCrediticio {
    EXCELENTE,   // nunca ha tenido mora
    BUENO,       // moras leves en el pasado, ya pagadas
    REGULAR,     // alguna mora reciente
    MALO,        // múltiples moras o reportes negativos
    SIN_HISTORIAL // cliente nuevo, sin historial en centrales
}
