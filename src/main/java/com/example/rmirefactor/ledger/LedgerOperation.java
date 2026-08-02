package com.example.rmirefactor.ledger;

import java.io.Serializable;

/**
 * The operation flag used by the intentionally generic baseline API.
 */
public enum LedgerOperation implements Serializable {
    ADD,
    SUBTRACT
}
