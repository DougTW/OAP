/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.clustering

import com.intel.daal.algorithms.KMeansResult
import com.intel.daal.data_management.data.{HomogenNumericTable, NumericTable, Matrix => DALMatrix}
import com.intel.daal.services.DaalContext
import org.apache.spark.ml.util._
import org.apache.spark.mllib.clustering.{DistanceMeasure, KMeansModel => MLlibKMeansModel}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.util.OneDAL.setNumericTableValue
import org.apache.spark.mllib.linalg.{Vector => OldVector, Vectors => OldVectors}
import org.apache.spark.rdd.RDD

class KMeansDALImpl (
  var nClusters : Int = 4,
  var maxIterations : Int = 10,
  var tolerance : Double = 1e-6,
  val distanceMeasure: String = DistanceMeasure.EUCLIDEAN,
  val centers: Array[OldVector] = null,
  val executorNum: Int,
  val executorCores: Int
) extends Serializable {

  def runWithRDDVector(data: RDD[Vector], instr: Option[Instrumentation]) : MLlibKMeansModel = {

    instr.foreach(_.logInfo(s"Processing partitions with $executorNum executors"))

    val partitionDims = Utils.getPartitionDims(data)
    val executorIPAddress = Utils.sparkFirstExecutorIP(data.sparkContext)

    val results = data.mapPartitionsWithIndex { (index: Int, it: Iterator[Vector]) =>

      // Set number of thread to use for each dal process, TODO: set through config
      //      OneDAL.setNumberOfThread(1)

      // Assume each partition of RDD[HomogenNumericTable] has only one NumericTable
//      val localData = p.next()


//      val arrayData = it.toArray
      val numRows = partitionDims(index)._1
      val numCols = partitionDims(index)._2

      println(s"KMeansDALImpl: Partition index: $index, numCols: $numCols, numRows: $numRows")
      println("KMeansDALImpl: Loading libMLlibDAL.so" )
      // extract libMLlibDAL.so to temp file and load
      LibUtils.loadLibrary()

      // Build DALMatrix
      val context = new DaalContext()
      val localData = new DALMatrix(context, classOf[java.lang.Double],
        numCols.toLong, numRows.toLong, NumericTable.AllocationFlag.DoAllocate)

      println(s"KMeansDALImpl: Start data conversion")

      val start = System.nanoTime
      it.zipWithIndex.foreach {
        case (v, rowIndex) =>
          for (colIndex <- 0 until numCols)
            // TODO: Add matrix.set API in DAL to replace this
            // matrix.set(rowIndex, colIndex, row.getString(colIndex).toDouble)
            setNumericTableValue(localData.getCNumericTable, rowIndex, colIndex, v(colIndex))
      }

      val duration = (System.nanoTime - start) / 1E9

      println(s"KMeansDALImpl: Data conversion takes $duration seconds")

//      Service.printNumericTable("10 rows of local input data", localData, 10)

      OneCCL.init(executorNum, executorIPAddress, OneCCL.KVS_PORT)

      val initCentroids = OneDAL.makeNumericTable(centers)
      var result = new KMeansResult()
      val cCentroids = cKMeansDALComputeWithInitCenters(
        localData.getCNumericTable,
        initCentroids.getCNumericTable,
        nClusters,
        maxIterations,
        executorNum,
        executorCores,
        result
      )

      val ret = if (OneCCL.isRoot()) {
        assert(cCentroids != 0)

        val centerVectors = OneDAL.numericTableToVectors(OneDAL.makeNumericTable(cCentroids))
        Iterator((centerVectors, result.totalCost))
      } else {
        Iterator.empty
      }

      OneCCL.cleanup()

      ret

    }.collect()

    // Make sure there is only one result from rank 0
    assert(results.length == 1)

    val centerVectors = results(0)._1
    val totalCost = results(0)._2

    //    printNumericTable(centers)

    //    Service.printNumericTable("centers", centers)

    instr.foreach(_.logInfo(s"OneDAL output centroids:\n${centerVectors.mkString("\n")}"))

    // TODO: tolerance support in DAL
    val iteration = maxIterations

    //    val centerVectors = OneDAL.numericTableToVectors(centers)

    val parentModel = new MLlibKMeansModel(
      centerVectors.map(OldVectors.fromML(_)),
      distanceMeasure, totalCost, iteration)

    parentModel
  }

  def run(data: RDD[HomogenNumericTable], instr: Option[Instrumentation]) : MLlibKMeansModel = {

    instr.foreach(_.logInfo(s"Processing partitions with $executorNum executors"))

    val results = data.mapPartitions { p =>

//      OneCCL.init(executorNum)

    OneCCL.init(executorNum, "10.0.0.138", OneCCL.KVS_PORT)

      // Set number of thread to use for each dal process, TODO: set through config
//      OneDAL.setNumberOfThread(1)

      // Assume each partition of RDD[HomogenNumericTable] has only one NumericTable
      val localData = p.next()

      val context = new DaalContext()
      localData.unpack(context)

      val initCentroids = OneDAL.makeNumericTable(centers)

      var result = new KMeansResult()
      val cCentroids = cKMeansDALComputeWithInitCenters(
        localData.getCNumericTable,
        initCentroids.getCNumericTable,
        nClusters,
        maxIterations,
        executorNum,
        executorCores,
        result
      )

      val ret = if (OneCCL.isRoot()) {
        assert(cCentroids != 0)

        val centerVectors = OneDAL.numericTableToVectors(OneDAL.makeNumericTable(cCentroids))
        Iterator((centerVectors, result.totalCost))
      } else {
        Iterator.empty
      }

      OneCCL.cleanup()

      ret

    }.collect()

    // Make sure there is only one result from rank 0
    assert(results.length == 1)

    val centerVectors = results(0)._1
    val totalCost = results(0)._2

//    printNumericTable(centers)

//    Service.printNumericTable("centers", centers)

    instr.foreach(_.logInfo(s"OneDAL output centroids:\n${centerVectors.mkString("\n")}"))

    // TODO: tolerance support in DAL
    val iteration = maxIterations

//    val centerVectors = OneDAL.numericTableToVectors(centers)

    val parentModel = new MLlibKMeansModel(
      centerVectors.map(OldVectors.fromML(_)),
      distanceMeasure, totalCost, iteration)

    parentModel
  }

  // Single entry to call KMeans DAL backend, output HomogenNumericTable representing centers
//  @native private def cKMeansDALCompute(data: Long, block_num: Int,
//                                        cluster_num: Int, iteration_num: Int) : Long

  // Single entry to call KMeans DAL backend with initial centers, output centers
  @native private def cKMeansDALComputeWithInitCenters(data: Long, centers: Long,
                                                       cluster_num: Int, iteration_num: Int,
                                                       executor_num: Int,
                                                       executor_cores: Int,
                                                       result: KMeansResult): Long

}