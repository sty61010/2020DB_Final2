package org.vanilladb.calvin.schedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.vanilladb.calvin.cache.InMemoryRecord;
import org.vanilladb.calvin.procedure.CalvinStoredProcedure;
import org.vanilladb.calvin.procedure.ExecutionPlan;
import org.vanilladb.calvin.server.Calvin;
import org.vanilladb.calvin.sql.PrimaryKey;
import org.vanilladb.core.sql.Constant;
import org.vanilladb.core.sql.DoubleConstant;

public class Vertex implements Comparable<Vertex>{
		public int indegree = 0;
		public int outdegree;
		public List<Vertex> dependentList = new ArrayList<Vertex>();
		public CalvinStoredProcedure<?> sp;
		
		public int clientId;
		public int connectionNum;
		public long txNum;
		
		public Vertex(CalvinStoredProcedure<?> sp, int clientId, int connectionNum, long txNum) {
			this.sp = sp;
			this.clientId = clientId;
			this.connectionNum = connectionNum;
			this.txNum = txNum;
		}
		
		public void findIntersection(Vertex v) {
			ExecutionPlan outerExecPlan = v.sp.getExecPlan();
			Set<PrimaryKey> outerUpdateKeys = outerExecPlan.getLocalUpdateKeys();
			Set<PrimaryKey> outerInsertKeys = outerExecPlan.getLocalInsertKeys();
			
			
			ExecutionPlan thisExecPlan = this.sp.getExecPlan();
			Set<PrimaryKey> localReadKeys = thisExecPlan.getLocalReadKeys();
			Set<PrimaryKey> updateIntersection = new HashSet<PrimaryKey>(outerUpdateKeys);
			updateIntersection.retainAll(localReadKeys);
			Set<PrimaryKey> insertIntersection = new HashSet<PrimaryKey>(outerInsertKeys);
			insertIntersection.retainAll(localReadKeys);
			
			if (updateIntersection.isEmpty() && insertIntersection.isEmpty()) {
				return;
			} else {
				this.indegree++;
				v.dependentList.add(this);
				
				if (!updateIntersection.isEmpty()) {
					v.sp.UpdateSet = updateIntersection;
					outerUpdateKeys.removeAll(updateIntersection);
				}
				
				if (!insertIntersection.isEmpty()) {
					v.sp.InsertSet = insertIntersection;
					outerUpdateKeys.removeAll(insertIntersection);
				}
				
			}
		}
		
		public void findPreWriteSet() {
			if (Calvin.BatchUpdateMap.isEmpty()) {
				return;
			} else {
				ExecutionPlan thisExecPlan = this.sp.getExecPlan();
				Set<PrimaryKey> localReadKeys = thisExecPlan.getLocalReadKeys();
				Set<PrimaryKey> updateIntersection = new HashSet<PrimaryKey>(localReadKeys);
				Set<PrimaryKey> buSet = Calvin.BatchUpdateMap.keySet();
				updateIntersection.retainAll(buSet);
				
				Set<PrimaryKey> insertIntersection = new HashSet<PrimaryKey>(localReadKeys);
				Set<PrimaryKey> biSet = Calvin.BatchInsertMap.keySet();
				insertIntersection.retainAll(biSet);
				
				if (updateIntersection.isEmpty() && insertIntersection.isEmpty()) {
					return;
				} else {
					for(PrimaryKey key: updateIntersection) {
						sp.update(key, Calvin.BatchUpdateMap.get(key));
						Calvin.BatchUpdateMap.remove(key);
					}
					
					for(PrimaryKey key: insertIntersection) {
						sp.insert(key, Calvin.BatchInsertMap.get(key));
						Calvin.BatchInsertMap.remove(key);
					}
				}
				
			}
		}

		@Override
		public int compareTo(Vertex o) {
			// TODO Auto-generated method stub
			return this.indegree - o.indegree;
		}
}