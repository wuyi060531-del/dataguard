package dataguard.service;

import dataguard.persistence.InspectionRepository;
import dataguard.persistence.StoredDataset;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DatasetService {
    private final InspectionRepository repository;
    private final FileStorage storage;

    public DatasetService(InspectionRepository repository, FileStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public DatasetInfo upload(String originalName, InputStream content) {
        return save(originalName, storage.store(originalName, content));
    }

    public DatasetInfo register(String relativePath) {
        String name = storage.importFileName(relativePath);
        return save(name, storage.importFile(relativePath));
    }

    public DatasetInfo getDataset(String id) {
        return stored(id).info();
    }

    public List<DatasetInfo> listDatasets(int limit, int offset) {
        validatePagination(limit, offset);
        return repository.listDatasets(limit, offset);
    }

    StoredDataset stored(String id) {
        validateId(id, "数据集");
        return repository.findDataset(id).orElseThrow(() ->
                new ServiceException("DATASET_NOT_FOUND", 404, "数据集不存在。"));
    }

    private DatasetInfo save(String name, FileStorage.ManagedFile managed) {
        DatasetInfo info = new DatasetInfo(UUID.randomUUID().toString(), name, managed.size(), managed.sha256(),
                Instant.ofEpochMilli(System.currentTimeMillis()));
        try {
            repository.insertDataset(new StoredDataset(info, managed.storageName()));
            return info;
        } catch (RuntimeException failure) {
            storage.remove(managed);
            if (failure instanceof DataAccessException) {
                throw new ServiceException("PERSISTENCE_ERROR", 503, "数据集暂时无法保存。", failure);
            }
            throw failure;
        }
    }

    static void validatePagination(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ServiceException("INVALID_PAGINATION", 400, "limit 必须为 1～100，offset 不能小于 0。");
        }
    }

    static void validateId(String id, String entity) {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ServiceException("INVALID_ID", 400, entity + " ID 必须是 UUID。");
        }
    }
}
