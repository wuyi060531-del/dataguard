package dataguard.persistence;

import dataguard.service.DatasetInfo;

/** Internal persistence record, never returned by the API. */
public record StoredDataset(DatasetInfo info, String storageName) {
}
