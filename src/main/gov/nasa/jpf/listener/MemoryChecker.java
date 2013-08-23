//
// Copyright (C) 2006 United States Government as represented by the
// Administrator of the National Aeronautics and Space Administration
// (NASA).  All Rights Reserved.
//
// This software is distributed under the NASA Open Source Agreement
// (NOSA), version 1.3.  The NOSA has been approved by the Open Source
// Initiative.  See the file NOSA-1.3-JPF at the top of the distribution
// directory tree for the complete NOSA document.
//
// THE SUBJECT SOFTWARE IS PROVIDED "AS IS" WITHOUT ANY WARRANTY OF ANY
// KIND, EITHER EXPRESSED, IMPLIED, OR STATUTORY, INCLUDING, BUT NOT
// LIMITED TO, ANY WARRANTY THAT THE SUBJECT SOFTWARE WILL CONFORM TO
// SPECIFICATIONS, ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR
// A PARTICULAR PURPOSE, OR FREEDOM FROM INFRINGEMENT, ANY WARRANTY THAT
// THE SUBJECT SOFTWARE WILL BE ERROR FREE, OR ANY WARRANTY THAT
// DOCUMENTATION, IF PROVIDED, WILL CONFORM TO THE SUBJECT SOFTWARE.
//
package gov.nasa.jpf.listener;
import gov.nasa.jpf.PropertyListenerAdapter;
import gov.nasa.jpf.jvm.JVM;
import gov.nasa.jpf.search.Search;

import java.util.Vector;

public class MemoryChecker extends PropertyListenerAdapter {

	private Vector<Long> memorySnapshot = new Vector<Long>();

	private int countInstruction = 0;	

	public void searchStarted(Search search) {
		super.searchStarted(search);
	}

	private long calcMemoryUsage(){
		long result = 0;
		for(Long value : memorySnapshot ){
			result = result + value;
		}

		result = result/memorySnapshot.size();

		result = result >> 20;

		return result;
	}


	@SuppressWarnings("unchecked")
	@Override
	/**
	 * JVM is about to execute instruction
	 */
	public void executeInstruction(JVM jvm) {
//		if(countInstruction == 0){
//			init();
//		}

//		if(SetFactory.checkMemory){
			countInstruction++; //TODO make this count instructions differents of null
			if( countInstruction % 1000 == 0){
				memorySnapshot.add(Runtime.getRuntime().totalMemory());
//			}
			//TODO use this to TODO above		
			//			insn = jvm.getNextInstruction();
			//			if (insn == null) {
			//				return;
			//			}

		}
	}


//	private void init() {
//		String checkMemory = (String) JVM.getVM().getConfig().get("check.memory");
//		if(checkMemory != null) SetFactory.checkMemory = Boolean.valueOf(checkMemory);
//	}

	public void searchFinished(Search search) {
		StringBuffer sb = new StringBuffer();

//		if(SetFactory.checkMemory){
			sb.append("Average Memory: ");
			sb.append(calcMemoryUsage());sb.append("MB\n");
//		}

		System.out.println(sb.toString());
	}



}