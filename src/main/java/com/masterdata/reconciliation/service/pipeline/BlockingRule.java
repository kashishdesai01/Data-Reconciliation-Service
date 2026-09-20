package com.masterdata.reconciliation.service.pipeline;

import com.masterdata.reconciliation.domain.model.NormalizedRecord;

enum BlockingRule {
    EMAIL("EXACT_EMAIL") {
        String key(NormalizedRecord record) {
            return present(record.email()) ? record.email() : null;
        }
    },
    PHONE("EXACT_PHONE") {
        String key(NormalizedRecord record) {
            return present(record.phoneE164()) ? record.phoneE164() : null;
        }
    },
    LAST7_SURNAME("PHONE_LAST7_SURNAME_SOUNDEX") {
        String key(NormalizedRecord record) {
            return join(record.phoneLast7(), record.surnameSoundex());
        }
    },
    SURNAME_POSTAL("SURNAME_SOUNDEX_POSTAL") {
        String key(NormalizedRecord record) {
            return join(record.surnameSoundex(), record.postalCode());
        }
    },
    NAME_STREET("FIRST_INITIAL_SURNAME_STREET_NUMBER") {
        String key(NormalizedRecord record) {
            String initial = present(record.firstName()) ? record.firstName().substring(0, 1) : null;
            return join(initial, record.surname(), record.streetNumber());
        }
    };

    final String label;

    BlockingRule(String label) {
        this.label = label;
    }

    abstract String key(NormalizedRecord record);

    private static String join(String... values) {
        for (String value : values) {
            if (!present(value)) return null;
        }
        return String.join("|", values);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
