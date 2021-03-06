/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intel.analytics.bigdl.nn

import com.intel.analytics.bigdl.nn.Graph.ModuleNode
import com.intel.analytics.bigdl.nn.abstractnn.{AbstractModule, Activity, TensorModule}
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils.{Node, T, Table}

import scala.reflect.ClassTag

/**
 * A graph container. Each node can have multiple inputs. The output of the node should be a tensor.
 * The output tensor can be connected to multiple nodes. So the module in each node can have a
 * tensor or table input, and should have a tensor output.
 *
 * The graph container can have multiple inputs and multiple outputs. If there's one input, the
 * input data fed to the graph module should be a tensor. If there're multiple inputs, the input
 * data fed to the graph module should be a table, which is actually an sequence of tensor. The
 * order of the input tensors should be same with the order of the input nodes. This is also
 * applied to the gradient from the module in the back propagation.
 *
 * All of the input modules must accept a tensor input. If your input module accept multiple
 * tensors as input, you should add some Input module before it as input nodes and connect the
 * output of the Input modules to that module.
 *
 * If there's one output, the module output is a tensor. If there're multiple outputs, the module
 * output is a table, which is actually an sequence of tensor. The order of the output tensors is
 * same with the order of the output modules. This is also applied to the gradient passed to the
 * module in the back propagation.
 *
 * All inputs should be able to connect to outputs through some paths in the graph. It is
 * allowed that some successors of the inputs node are not connect to outputs. If so, these nodes
 * will be excluded in the computation.
 *
 * @param inputs input nodes
 * @param outputs output nodes
 * @tparam T Numeric type. Only support float/double now
 */
@SerialVersionUID(- 2896121321564992779L)
class Graph[T: ClassTag](inputs : Seq[ModuleNode[T]],
  outputs : Seq[ModuleNode[T]])(implicit ev: TensorNumeric[T])
  extends Container[Activity, Activity, T]{

  override def updateOutput(input: Activity): Activity = {
    var i = 0
    if (inputs.length == 1) {
      // A tensor is expected here
      inputsBP(i) = input.toTensor[T]
      inputs(i).element.updateOutput(inputsBP(i))
      i += 1
    } else {
      val inputTable = input.toTable
      require(inputTable.length() == inputs.length,
        s"Graph.updateOutput: Input tensor number(${inputTable.length()}) is not equal to " +
          s"inputs node number(${inputs.length})")
      while(i < inputTable.length()) {
        // Each element of the table must be a tensor
        inputsBP(i) = inputTable[Tensor[T]](i + 1)
        inputs(i).element.updateOutput(inputsBP(i))
        i += 1
      }
    }

    while(i < executions.length) {
      val node = executions(i)
      inputsBP(i) = if (node.prevNodes.length == 1) {
        node.prevNodes.head.element.output.toTensor[T]
      } else {
        seqToTable(node.prevNodes.map(_.element.output))
      }
      node.element.updateOutput(inputsBP(i))
      i += 1
    }

    output = if (outputs.length == 1) {
      outputs(0).element.output
    } else {
      seqToTable(outputs.map(_.element.output))
    }
    output
  }

  override def updateGradInput(input: Activity, gradOutput: Activity): Activity = {
    var j = outputs.length - 1
    var i = executions.length - 1
    if (outputs.length == 1) {
      // Expect a tensor here
      outputs(j).element.updateGradInput(inputsBP(i), gradOutput.toTensor)
      j -= 1
      i -= 1
    } else {
      val gradOutputTable = gradOutput.toTable
      require(gradOutputTable.length() == outputs.length,
        s"Graph.updateGradInput:gradient tensor number(${gradOutputTable.length()}) is not " +
          s"equal to outputs node number(${outputs.length})")
      while(j >= 0) {
        outputs(j).element.updateGradInput(inputsBP(i), gradOutputTable[Tensor[T]](j + 1))
        j -= 1
        i -= 1
      }
    }

    while(i >= 0) {
      val curNode = executions(i)
      var curGradOutput : Tensor[T] = null
      curNode.nextNodes.foreach(n => {
        val nextGradOutput = if (n.prevNodes.length == 1) {
          n.element.gradInput.toTensor
        } else {
          val nextGradOutputTable = n.element.gradInput.toTable
          nextGradOutputTable[Tensor[T]](n.prevNodes.indexOf(curNode) + 1)
        }

        if (curGradOutput == null) {
          curGradOutput = nextGradOutput
        } else {
          curGradOutput.add(nextGradOutput)
        }
      })
      gradOutputBP(i) = curGradOutput
      curNode.element.updateGradInput(inputsBP(i), curGradOutput)
      i -= 1
    }

    gradInput = if (inputs.length == 1) {
      inputs(0).element.gradInput
    } else {
      seqToTable(inputs.map(_.element.gradInput))
    }

    gradInput
  }

  override def accGradParameters(input: Activity, gradOutput: Activity, scale: Double): Unit = {
    var j = outputs.length - 1
    var i = executions.length - 1
    if (outputs.length == 1) {
      // Expect a tensor here
      outputs(j).element.accGradParameters(inputsBP(i), gradOutput.toTensor)
      j -= 1
      i -= 1
    } else {
      val gradOutputTable = gradOutput.toTable
      require(gradOutputTable.length() == outputs.length,
        s"Graph.accGradParameters: gradient tensor number(${gradOutputTable.length()}) is not " +
          s"equal to outputs node number(${outputs.length})")
      while(j >= 0) {
        outputs(j).element.accGradParameters(inputsBP(i), gradOutputTable[Tensor[T]](j + 1))
        j -= 1
        i -= 1
      }
    }

    while(i >= 0) {
      val curNode = executions(i)
      curNode.element.accGradParameters(inputsBP(i), gradOutputBP(i))
      i -= 1
    }
  }

  override def add(module: AbstractModule[_ <: Activity, _ <: Activity, T]): Graph.this.type = {
    throw new IllegalArgumentException("Graph: Please don't use add method in Graph container. " +
      "A graph container should not be changed after it is constructed")
  }

  // Add a dummy output node, to get an one end graph. So the nodes that are not dependent by
  // the outputs will be excluded
  private val dummyOutput = new ModuleNode[T](null)
  outputs.foreach(_ -> dummyOutput)
  private val backGraph = dummyOutput.graph(reverse = true)

  // Build execution plan
  private val executions = backGraph.topologySort.filter(_.element != null).reverse
  modules.appendAll(executions.map(_.element.asInstanceOf[AbstractModule[Activity, Activity, T]]))

  // Check all inputs of the graph should be passed in
  checkRoots

  // Make input output nodes in executions be same order with how they are passed in
  {
    // Input nodes are not together, move them together
    var i = 0
    while(i < inputs.length) {
      shift(executions, executions.indexOf(inputs(i)), i)
      i += 1
    }

    // Output nodes are together
    val offset = executions.length - outputs.length
    i = 0
    while(i < outputs.length) {
      executions(i + offset) = outputs(i)
      i += 1
    }
  }

  private val inputsBP = new Array[Activity](executions.length)
  private val gradOutputBP = new Array[Tensor[T]](executions.length)

  private def checkRoots : Unit = {
    val roots = executions.filter(_.prevNodes.size == 0)
    require(roots.size == inputs.length,
      s"There're ${inputs.length} inputs, but graph has ${roots.size} roots")
    inputs.foreach(n =>
      require(roots.contains(n), "inputs and graph roots are not match")
    )
  }

  private[nn] def shift[B](data : Array[B], from : Int, to : Int): Array[B] = {
    require(from < data.length && from >= 0, s"invalid from $from array length is ${data.length}")
    require(to < data.length && to >= 0, s"invalid to $to array length is ${data.length}")
    if (from == to) {
      data
    } else if (from < to) {
      var i = from
      while(i < to) {
        val tmp = data(i)
        data(i) = data(i + 1)
        data(i + 1) = tmp
        i += 1
      }
      data
    } else {
      var i = from
      while(i > to) {
        val tmp = data(i)
        data(i) = data(i - 1)
        data(i - 1) = tmp
        i -= 1
      }
      data
    }
  }

  private def seqToTable(inputs: Seq[_]) : Table = {
    val t = T()
    var j = 1
    inputs.foreach(tensor => {
      t(j) = tensor
      j += 1
    })
    t
  }
}

object Graph {
  /**
   * Node for graph container. The module should have a tensor/table input while a tensor output
   * @tparam T
   */
  type ModuleNode[T] = Node[AbstractModule[Activity, Tensor[T], T]]

  /**
   * Build multiple inputs, multiple outputs graph container.
   * @param input input node
   * @param output output node
   * @return a graph container
   */
  def apply[T: ClassTag](input : Array[ModuleNode[T]], output : Array[ModuleNode[T]])
    (implicit ev: TensorNumeric[T]) : Graph[T] = {
    new Graph[T](input, output)
  }

  /**
   * Build a single input, multiple outputs graph container
   * @param input input node
   * @param output output nodes
   * @return a graph container
   */
  def apply[T: ClassTag](input : ModuleNode[T], output : Array[ModuleNode[T]])
    (implicit ev: TensorNumeric[T]) : Graph[T] = {
    new Graph[T](Array(input), output)
  }

  /**
   * Build a multiple inputs, single output graph container
   * @param input input nodes
   * @param output output node
   * @return a graph container
   */
  def apply[T: ClassTag](input : Array[ModuleNode[T]], output : ModuleNode[T])
    (implicit ev: TensorNumeric[T]) : Graph[T] = {
    new Graph[T](input, Array(output))
  }

  /**
   * Build a single input, single output graph container
   * @param input input nodes
   * @param output output nodes
   * @return a graph container
   */
  def apply[T: ClassTag](input : ModuleNode[T], output : ModuleNode[T])
    (implicit ev: TensorNumeric[T]) : Graph[T] = {
    new Graph[T](Array(input), Array(output))
  }
}

/**
 * Each input node of the graph container should accept one tensor as input. If you want a module
 * accepting multiple tensors as input, you should add some Input module before it and connect
 * the outputs of the Input nodes to it.
 * @tparam T The numeric type in the criterion, usually which are [[Float]] or [[Double]]
 */
@SerialVersionUID(- 8525406230282608924L)
class Input[T: ClassTag]()(implicit ev: TensorNumeric[T]) extends TensorModule[T] {
  override def updateOutput(input: Tensor[T]): Tensor[T] = {
    output = input
    output
  }
  override def updateGradInput(input: Tensor[T], gradOutput: Tensor[T]): Tensor[T] = {
    gradInput = gradOutput
    gradInput
  }
}

object Input {
  def apply[T: ClassTag]()(implicit ev: TensorNumeric[T]): ModuleNode[T] = {
    new Node(new Input().asInstanceOf[AbstractModule[Activity, Tensor[T], T]])
  }
}
