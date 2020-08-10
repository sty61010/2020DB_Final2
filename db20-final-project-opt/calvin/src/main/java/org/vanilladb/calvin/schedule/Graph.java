package org.vanilladb.calvin.schedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.vanilladb.calvin.procedure.CalvinStoredProcedure;
import org.vanilladb.calvin.procedure.ExecutionPlan;
import org.vanilladb.calvin.server.Calvin;
import org.vanilladb.calvin.sql.PrimaryKey;
import org.vanilladb.core.sql.Constant;
import org.vanilladb.core.sql.DoubleConstant;

public class Graph {
	
	PriorityQueue<Vertex> graph = new PriorityQueue<Vertex>();
	
	public Graph(List<Vertex> vlist) {
		
		Vertex newV;
		while (!vlist.isEmpty()) {
			newV = vlist.remove(0);
			newV.findPreWriteSet();
			for(Vertex v: graph) {
				newV.findIntersection(v);
			}
			this.graph.add(newV);
		}
		
	}
	
	public Vertex getVertex() {
		if (graph.isEmpty()) {
			return null;
		}
		Vertex v = graph.peek();
		for(Vertex tmp: v.dependentList) {
			tmp.indegree--;
		}
//		System.out.println("Indegree: " + v.indegree + ", txNum: " + v.txNum);
		return graph.poll();
	}
	
	public boolean isEmpty() {
		return graph.isEmpty();
	}
	
}