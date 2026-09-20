package com.masterdata.reconciliation.api.model;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor) {
}
