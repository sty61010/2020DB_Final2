package org.vanilladb.calvin.cache;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.vanilladb.calvin.server.Calvin;
import org.vanilladb.calvin.sql.PrimaryKey;
import org.vanilladb.core.sql.Constant;
import org.vanilladb.core.storage.tx.Transaction;

public class TransactionCache {
	
	// For single thread
	private Transaction tx;
	private Map<PrimaryKey, InMemoryRecord> cachedRecords;
	private Set<PrimaryKey> dirtyKeys;

	TransactionCache(Transaction tx) {
		this.tx = tx;
		this.cachedRecords = new ConcurrentHashMap<PrimaryKey, InMemoryRecord>();
		this.dirtyKeys = new HashSet<PrimaryKey>();
	}
	
	public InMemoryRecord readFromLocal(PrimaryKey key) {
		InMemoryRecord rec = cachedRecords.get(key);
		if (rec != null)
			return rec;
		rec = Calvin.cacheMgr().getCachedRecord(key);
		if (rec != null) {
			cachedRecords.put(key, rec);
			return rec;
		}
		rec = VanillaCoreStorage.read(key, tx);
		if (rec != null) {
			cachedRecords.put(key, rec);
			Calvin.cacheMgr().cacheRecord(key, rec, tx);
		}
		return rec;
	}
	
	public InMemoryRecord readFromRemote(PrimaryKey key) {
		InMemoryRecord rec = cachedRecords.get(key);
		if (rec != null)
			return rec;
		try {
			String name = Thread.currentThread().getName();
			Thread.currentThread().setName(name + " waits for " + key + " from remote.");
			
			// Wait for remote records
			synchronized (cachedRecords) {
				rec = cachedRecords.get(key);
				while (rec == null) {
					cachedRecords.wait();
					rec = cachedRecords.get(key);
				}
			}
			
			Thread.currentThread().setName(name);
			
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		return rec;
	}

	public void update(PrimaryKey key, InMemoryRecord rec) {
		cachedRecords.put(key, rec);
		dirtyKeys.add(key);
		Calvin.cacheMgr().cacheRecord(key, rec, tx);
	}
	
	public void insert(PrimaryKey key, InMemoryRecord rec) {
		rec.setNewInserted();
		cachedRecords.put(key, rec);
		//VanillaCoreStorage.insert(key, rec, tx);
		dirtyKeys.add(key);
	}

	public void insert(PrimaryKey key, Map<String, Constant> fldVals) {
		InMemoryRecord rec = InMemoryRecord.newRecordForInsertion(key, fldVals);
		cachedRecords.put(key, rec);
		//VanillaCoreStorage.insert(key, rec, tx);
		dirtyKeys.add(key);
	}

	public void delete(PrimaryKey key) {
		InMemoryRecord dummyRec = InMemoryRecord.newRecordForDeletion(key);
		cachedRecords.put(key, dummyRec);
		//Calvin.cacheMgr().delete(key, tx);
		dirtyKeys.add(key);
	}
	
	public void flush() {
		/*for (Map.Entry<PrimaryKey, InMemoryRecord> entry: cachedRecords.entrySet()) {
			InMemoryRecord rec = entry.getValue();
			PrimaryKey key = entry.getKey();
			// store in cacheMgr
			if(key.getTableName().equals("warehouse") || key.getTableName().equals("district"))
				Calvin.cacheMgr().cacheRecord(key, rec);
			
			else {
				if (rec.isDeleted())
					VanillaCoreStorage.delete(key, tx);
				else if (rec.isNewInserted())
					VanillaCoreStorage.insert(key, rec, tx);
				else if (rec.isDirty())
					VanillaCoreStorage.update(key, rec, tx);
			}	
			//Calvin.cacheMgr().cacheRecord(key, rec);
		}*/
		for(PrimaryKey key: this.dirtyKeys) {
			InMemoryRecord rec = this.cachedRecords.get(key);
			if (rec.isDeleted())
				Calvin.cacheMgr().delete(key, tx);
			else if (rec.isNewInserted()) 
				VanillaCoreStorage.insert(key, rec, tx);
			else Calvin.cacheMgr().cacheRecord(key, rec, tx);
		}
		dirtyKeys.clear();
		cachedRecords.clear();
	}
	
	void onReceivedRecord(InMemoryRecord record) {
		synchronized (cachedRecords) {
			cachedRecords.put(record.getPrimaryKey(), record);
			cachedRecords.notifyAll();
		}
	}
}
