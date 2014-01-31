/*
 * Copyright 2013 University of Chicago and Argonne National Laboratory
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
 * limitations under the License
 */
package exm.stc.ic.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import exm.stc.common.CompilerBackend;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.CompileTimeArgs;
import exm.stc.common.lang.ForeignFunctions;
import exm.stc.common.lang.ForeignFunctions.SpecialFunction;
import exm.stc.common.lang.Operators;
import exm.stc.common.lang.Operators.BuiltinOpcode;
import exm.stc.common.lang.PassedVar;
import exm.stc.common.lang.Redirects;
import exm.stc.common.lang.RefCounting;
import exm.stc.common.lang.RefCounting.RefCountType;
import exm.stc.common.lang.TaskMode;
import exm.stc.common.lang.TaskProp.TaskProps;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.util.Counters;
import exm.stc.common.util.Pair;
import exm.stc.ic.ICUtil;
import exm.stc.ic.opt.Semantics;
import exm.stc.ic.opt.valuenumber.ComputedValue;
import exm.stc.ic.opt.valuenumber.ValLoc;
import exm.stc.ic.opt.valuenumber.ValLoc.Closed;
import exm.stc.ic.opt.valuenumber.ValLoc.IsAssign;
import exm.stc.ic.tree.Conditionals.Conditional;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.ICTree.Function;
import exm.stc.ic.tree.ICTree.GenInfo;
import exm.stc.ic.tree.ICTree.Program;
import exm.stc.ic.tree.ICTree.RenameMode;
import exm.stc.ic.tree.ICTree.Statement;
import exm.stc.ic.tree.ICTree.StatementType;
/**
 * This class contains instructions used in the intermediate representation.
 * Each instruction is responsible for making particular modifications to
 * itself, and for reporting particular information about itself. The
 * Instruction interface has a number of methods that each instruction
 * must implement for this purpose.
 *
 */
public class ICInstructions {

  public static abstract class Instruction implements Statement {
    public final Opcode op;
  
    public Instruction(Opcode op) {
      super();
      this.op = op;
    }
    

    public StatementType type() {
      return StatementType.INSTRUCTION;
    }
    public Conditional conditional() {
      throw new STCRuntimeError("Not a conditional");
    }
    public Instruction instruction() {
      return this;
    }
  
    /**
     * @return a short name for the operation used for human-readable
     *        diagnostics 
     */
    public String shortOpName() {
      return op.toString().toLowerCase();
    }
    
    @Override
    public void setParent(Block parent) {
      // Do nothing
    }
   
    public void removeVars(Set<Var> removeVars) {
      // default impl: do nothing
    }
  
    /**
     * Replace instruction variables according to mode
     * @param renames
     */
    public abstract void renameVars(Map<Var, Arg> renames, RenameMode mode);

    @Override
    public abstract String toString();
    
    public void prettyPrint(StringBuilder sb, String indent) {
      sb.append(indent);
      sb.append(this.toString());
      sb.append("\n");
    }
  
    public abstract void generate(Logger logger, CompilerBackend gen,
            GenInfo info);
  
  
    /** List of variables the instruction reads */
    public abstract List<Arg> getInputs();
  
    /** List of variables the instruction writes */
    public abstract List<Var> getOutputs();
    
    public Arg getInput(int i) {
      return getInputs().get(i);
    }
    
    public Var getOutput(int i) {
      return getOutputs().get(i);
    }
  
    public abstract boolean hasSideEffects();
  
    /**
     * @return true if it is safe to change timing relative
     * to other tasks (e.g. if it is necessary that it should
     * return an up to date version of something
     */
    public boolean canChangeTiming() {
      return !hasSideEffects();
    }

    public boolean writesAliasVar() {
      // Writes to alias variables can have non-local effects
      for (Var out: this.getOutputs()) {
        if (out.storage() == Alloc.ALIAS) {
          return true;
        }
      }
      return false;
    }
    
    public boolean writesMappedVar() {
      // Writes to alias variables can have non-local effects
      for (Var out: this.getOutputs()) {
        if (out.mapping() != null) {
          return true;
        }
      }
      return false;
    }
    
    public static class MakeImmRequest {
      public final List<Var> out;
      public final List<Var> in;
      /** Where immediate code should run.  Default is local: in the current context */
      public final TaskMode mode;
      /** If inputs should be recursively closed */
      public final boolean recursiveClose;
      /** If outputs should have mapping initialized */
      public final boolean mapOutVars;
      
      public MakeImmRequest(List<Var> out, List<Var> in) {
        this(out, in, TaskMode.LOCAL);
      }
      
      public MakeImmRequest(List<Var> out, List<Var> in, TaskMode mode) {
        this(out, in, mode, false);
      }
      public MakeImmRequest(List<Var> out, List<Var> in, TaskMode mode,
                            boolean recursiveClose) {
        this(out, in, mode, recursiveClose, true);
      } 
      public MakeImmRequest(List<Var> out, List<Var> in, TaskMode mode,
          boolean recursiveClose, boolean mapOutVars) {
        this.out = out;
        this.in = in;
        this.mode = mode;
        this.recursiveClose = recursiveClose;
        this.mapOutVars = mapOutVars;
      }
    }
    
    /**
     * Interface to let instruction logic create variables
     */
    public interface VarCreator {
      public Var createDerefTmp(Var toDeref);
    }

    public static class MakeImmChange {
      /** Optional: if the output variable of op changed */
      public final Var newOut;
      public final Var oldOut;
      
      /** Whether caller should store output results */
      public final boolean storeOutputVals;
      public final Instruction newInsts[];
      
      /**
       * If the output variable changed from reference to plain future
       * @param newOut
       * @param oldOut
       * @param newInst
       */
      public MakeImmChange(Var newOut, Var oldOut, Instruction newInst) {
        this(newOut, oldOut, new Instruction[] {newInst});
      }
      
      /**
       * If the output variable changed from reference to plain future
       * @param newOut
       * @param oldOut
       * @param newInsts
       */
      public MakeImmChange(Var newOut, Var oldOut, Instruction newInsts[]) {
        this(newOut, oldOut, newInsts, true);
      }
      
      public MakeImmChange(Var newOut, Var oldOut, Instruction newInsts[],
          boolean storeOutputVals) {
        this.newOut = newOut;
        this.oldOut = oldOut;
        this.newInsts = newInsts;
        this.storeOutputVals = storeOutputVals;
      }
      
      /**
       * If we're just changing the instruction
       * @param newInst
       */
      public MakeImmChange(Instruction newInst) {
        this(null, null, newInst);
      }
      
      /**
       * If we're just changing the instructions
       * @param newInsts
       */
      public MakeImmChange(Instruction newInsts[]) {
        this(null, null, newInsts);
      }
      
      public MakeImmChange(Instruction[] newInsts, boolean storeOutputVals) {
        this(null, null, newInsts, storeOutputVals);
      }

      /**
       * Does the new instruction have a different output to the
       * old one
       * @return
       */
      public boolean isOutVarSame() {
        return newOut == null;
      }
    }
    
    public static class Fetched<V> {
      public Fetched(Var original, V fetched) {
        super();
        this.original = original;
        this.fetched = fetched;
      }
      public final Var original;
      public final V fetched;
      
      public static <T> List<Fetched<T>> makeList(
          List<Var> original, List<T> fetched) {
        // Handle nulls gracefully
        if (original == null) {
          original = Collections.emptyList();
        }
        if (fetched == null) {
          fetched = Collections.emptyList();
        }
        assert(original.size() == fetched.size());
        List<Fetched<T>> result = new ArrayList<Fetched<T>>(fetched.size());
        for (int i = 0; i < fetched.size(); i++) {
          result.add(new Fetched<T>(original.get(i), fetched.get(i)));
        }
        return result;
      }

      public static <T> List<T> getFetched(List<Fetched<T>> fetched) {
        List<T> res = new ArrayList<T>(fetched.size());
        for (Fetched<T> f: fetched) {
          res.add(f.fetched);
        }
        return res;
      }

      public static <T> T findFetched(Collection<Fetched<T>> fetched, Var v) {
        for (Fetched<T> f: fetched) {
          if (v.equals(f.original)) {
            return f.fetched;
          }
        }
        return null;
      }
      
      /**
       * Find fetched and cast to var if returned
       * @param fetched
       * @param v
       * @return
       */
      public static Var findFetchedVar(Collection<Fetched<Arg>> fetched, Var v) {
        Arg res = findFetched(fetched, v);
        return (res == null) ? null : res.getVar(); 
      }
      
      public String toString() {
        return "Fetched: " + original.toString() + " => " + fetched.toString();
      }
    }
    
    /**
     * 
     * @param closedVars variables closed at point of current instruction
     * @param waitForClose if true, allowed to (must don't necessarily
     *        have to) request that unclosed vars be waited for
     * @return null if it cannot be made immediate, if true,
     *            a list of vars that are the variables whose values are needed
     *            and output vars that need to be have value vars created
     */
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars,
                                           boolean waitForClose) {
      // Not implemented
      return null;
    }

    /**
     * Called to actually perform change requested
     * @param outVals any output values loaded
     * @param inValues any input values loaded
     * @return
     */
    public MakeImmChange makeImmediate(VarCreator creator,
                                       List<Fetched<Var>> outVals,
                                       List<Fetched<Arg>> inValues) {
      throw new STCRuntimeError("makeImmediate not valid  on " + type());
    }

    /**
     * @param prog the program.  Used to lookup function definitions
     * @return non-null the futures this instruction will block on
     *        it is ok if it forgets variables which aren't blocked on,
     *        but all variables returned must be blocked on
     */
    public abstract List<Var> getBlockingInputs(Program prog);
    
    /**
     * Some instructions will spawn off asynchronous tasks
     * @return SYNC if nothing spawned, otherwise the variety of task spawned
     */
    public abstract TaskMode getMode();
    
    /**
     * @return List of outputs closed immediately after instruction returns
     */
    public List<Var> getClosedOutputs() {
      return Var.NONE; // Default - assume nothing closed
    }

    /**
     * @return List of outputs that are piecewise assigned
     */
    public List<Var> getPiecewiseAssignedOutputs() {
      return Var.NONE;
    }
    
    public static enum InitType {
      PARTIAL, // In case multiple init instructions are needed
      FULL;    // If this fully initializes variable
    }
    /**
     * @return list of vars initialized by this instruction
     */
    public List<Pair<Var, InitType>> getInitialized() {
      return Collections.emptyList();
    }
    
    /**
     * Return true if var is initialized by instruction
     * @param var
     * @return
     */
    public boolean isInitialized(Var var) {
      for (Pair<Var, InitType> init: getInitialized()) {
        if (var.equals(init.val1)) {
          return true;
        }
      }
      return false;
    }
    
    /**
     * @return list of output variables that are actually modified
     *      typically this is all outputs, but in some special cases
     *      this is not true.  This is important to know for dead
     *      code elimination as sometimes we can safely eliminate an
     *      instruction even if all outputs can't be eliminated
     */
    public List<Var> getModifiedOutputs() {
      return this.getOutputs();
    }
    
    /**
     * @param fns map of functions (can optionally be null)
     * @return list of outputs for which previous value is read
     */
    public List<Var> getReadOutputs(Map<String, Function> fns) {
      return Var.NONE;
    }
    
    public final List<Var> getReadOutputs() {
      return getReadOutputs(null);
    }

    /**
     * @return priority of task spawned, if any.  null if no spawn or
     *      default priority
     */
    public TaskProps getTaskProps() {
      return null;
    }
    
    /**
     * @return a list of all values computed by expression.  Each ComputedValue
     *        returned should have the out field set so we know where to find 
     *        it 
     */
    public abstract List<ValLoc> getResults();
    
    @Override
    public Statement cloneStatement() {
      return clone();
    }
    
    public abstract Instruction clone();

    
    public Pair<List<Var>, List<Var>> getIncrVars(Map<String, Function> functions) {
      return getIncrVars();
    }

    /**
     * @return (read vars to be incremented, write vars to be incremented)
     */
    protected Pair<List<Var>, List<Var>> getIncrVars() {
      return Pair.create(getReadIncrVars(), getWriteIncrVars());
    }

    /**
     * @return list of vars that need read refcount increment
     */
    public List<Var> getReadIncrVars() {
      return Var.NONE;
    }

    /**
     * @return list of vars that need write refcount increment
     */
    public List<Var> getWriteIncrVars() {
      return Var.NONE;
    }

    /**
     * Try to piggyback increments or decrements to instruction
     * @param increments count of increment or decrement operations per var
     * @param type
     * @return empty list if not successful, otherwise list of vars for which
     *      piggyback occurred
     *          
     */
    public List<Var> tryPiggyback(Counters<Var> increments, RefCountType type) {
      return Var.NONE;
    }

    /**
     * If this instruction makes an output a part of another
     * variable such that modifying the output modifies something
     * else
     * @return null if nothing
     */
    public Pair<Var, Var> getComponentAlias() {
      // Default is nothing, few instructions do this
      return null;
    }

    /**
     * @return true if side-effect or output modification is idempotent
     */
    public boolean isIdempotent() {
      return false;
    }
  }

  public static class Comment extends Instruction {
    private final String text;
    public Comment(String text) {
      super(Opcode.COMMENT);
      this.text = text;
    }
  
    @Override
    public String toString() {
      return "# " + text;
    }
  
    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      gen.addComment(text);
    }
  
    @Override
    public void renameVars(Map<Var, Arg> renames, RenameMode mode) {
      // Don't do anything
    }
  
    @Override
    public List<Arg> getInputs() {
      return new ArrayList<Arg>(0);
    }
  
    @Override
    public List<Var> getOutputs() {
      return new ArrayList<Var>(0);
    }
  
    @Override
    public boolean hasSideEffects() {
      return false;
    }

    @Override
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars, 
                                           boolean waitForClose) {
      return null;
    }

    @Override
    public List<Var> getBlockingInputs(Program prog) {
      return Var.NONE;
    }

    @Override
    public List<ValLoc> getResults() {
      return null;
    }

    @Override
    public Instruction clone() {
      return new Comment(this.text);
    }

    @Override
    public TaskMode getMode() {
      return TaskMode.SYNC;
    }
  }

  // Maximum number of array element CVs to insert
  public static final long MAX_ARRAY_ELEM_CVS = 128L;
  
  public static abstract class CommonFunctionCall extends Instruction {
    protected final String functionName;
    protected final List<Var> outputs;
    protected final List<Arg> inputs;
    protected final TaskProps props;
    
    private final boolean hasUpdateableInputs;
    
    public CommonFunctionCall(Opcode op, String functionName,
        List<Var> outputs, List<Arg> inputs, TaskProps props) {
      super(op);
      this.functionName = functionName;
      this.outputs = new ArrayList<Var>(outputs);
      this.inputs = new ArrayList<Arg>(inputs);
      this.props = props;
      

      boolean hasUpdateableInputs = false; 
      for(Arg v: inputs) {
        assert(v != null);
        if (v.isVar() && Types.isScalarUpdateable(v.getVar())) {
          hasUpdateableInputs = true;
        }
      }
      this.hasUpdateableInputs = hasUpdateableInputs;
    }

    public String functionName() {
      return functionName;
    }
    
    /**
    * @return function input arguments
    */
    public List<Arg> getFunctionInputs() {
      return Collections.unmodifiableList(inputs);
    }


    public Arg getFunctionInput(int i) {
      return inputs.get(i);
    }
    
    /**
    * @return function output arguments
    */
    public List<Var> getFunctionOutputs() {
      return Collections.unmodifiableList(outputs);
    }


    public Var getFunctionOutput(int i) {
      return outputs.get(i);
    }
    
    @Override
    public List<Arg> getInputs() {
      List<Arg> inputVars = new ArrayList<Arg>(inputs);
      if (treatUpdInputsAsOutputs()) {
        // Remove updateable inputs from list
        ListIterator<Arg> it = inputVars.listIterator();
        while (it.hasNext()) {
          Arg in = it.next();
          if (in.isVar() && Types.isScalarUpdateable(in.getVar())) {
            it.remove();
          }
        }
      }
      // Need to include any properties as inputs
      if (props != null) {
        inputVars.addAll(props.values());
      }
      return inputVars;
    }

    /**
     * Return subset of input list which are variables
     * @param noValues
     * @return
     */
    protected List<Var> varInputs(boolean noValues) {
      List<Var> varInputs = new ArrayList<Var>();
      for (Arg input: inputs) {
        if (input.isVar()) {
          if (!noValues || !Types.isPrimValue(input.type())) {
            varInputs.add(input.getVar());
          }
        }
      }
      return varInputs;
    }

    @Override
    public List<Var> getOutputs() {
      if (!treatUpdInputsAsOutputs()) {
        return Collections.unmodifiableList(outputs);
      } else {
        List<Var> realOutputs = new ArrayList<Var>();
        realOutputs.addAll(outputs);
        addAllUpdateableInputs(realOutputs);
        return realOutputs;
      }
    }

    private void addAllUpdateableInputs(List<Var> realOutputs) {
      for (Arg in: inputs) {
        if (in.isVar() && Types.isScalarUpdateable(in.getVar())) {
          realOutputs.add(in.getVar());
        }
      }
    }

    @Override
    public List<Var> getReadOutputs(Map<String, Function> fns) {
      switch (op) {
        case CALL_FOREIGN: 
        case CALL_FOREIGN_LOCAL: {
          List<Var> res = new ArrayList<Var>();
          // Only some output types might be read
          for (Var o: outputs) {
            if (Types.hasReadableSideChannel(o.type())) {
              res.add(o);
            }
          }
          if (treatUpdInputsAsOutputs()) {
            addAllUpdateableInputs(res);
          }
          return res;
        }
        case CALL_LOCAL:
        case CALL_LOCAL_CONTROL:
        case CALL_SYNC:
        case CALL_CONTROL: {
          List<Var> res = new ArrayList<Var>();
          Function f = fns == null ? null : fns.get(this.functionName);
          for (int i = 0; i < outputs.size(); i++) {
            Var o = outputs.get(i);

            // Check to see if function might read the output
            if (Types.hasReadableSideChannel(o.type()) &&
                (f == null || !f.isOutputWriteOnly(i))) {
              res.add(o);
            }
          }

          if (treatUpdInputsAsOutputs()) {
            addAllUpdateableInputs(res);
          }
          return res;
        }
        default:
          throw new STCRuntimeError("unexpected op: " + op);
      }
    }

    
    @Override
    public String shortOpName() {
      return op.toString().toLowerCase() + "-" + functionName;
    }
    
    @Override
    public TaskProps getTaskProps() {
      // Return null if not found
      return props;
    }
    
    /**
     * @param fnCallOp
     * @return true if arguments should be local values
     */
    public static boolean acceptsLocalValArgs(Opcode fnCallOp) {
      switch (fnCallOp) {
        case CALL_CONTROL:
        case CALL_SYNC:
        case CALL_LOCAL:
        case CALL_LOCAL_CONTROL:
        case CALL_FOREIGN:
          return false;
        case CALL_FOREIGN_LOCAL:
          return true;
        default:
          throw new STCRuntimeError("Unexpected op: " + fnCallOp);
      }
    }
    
    private boolean isCopyFunction() {
      if (ForeignFunctions.isCopyFunction(functionName)) {
        return true;
      } else if (ForeignFunctions.isMinMaxFunction(functionName)
              && getInput(0).equals(getInput(1))) {
        return true;
      }
      return false;
    }

    @Override
    public boolean hasSideEffects() {
      return (!ForeignFunctions.isPure(functionName));
    }
    
    @Override
    public List<ValLoc> getResults() {
      if (ForeignFunctions.isPure(functionName)) {
        if (isCopyFunction()) {
          // Handle copy as a special case
          return ValLoc.makeCopy(getOutput(0), getInput(0),
                                 IsAssign.TO_LOCATION).asList();
        } else {
          List<ValLoc> res = new ArrayList<ValLoc>();
          for (int output = 0; output < getOutputs().size(); output++) {
            Closed outputClosed = Closed.MAYBE_NOT;// safe assumption
            String canonicalFunctionName = this.functionName;
            List<Arg> inputs = getInputs();
            List<Arg> cvArgs = new ArrayList<Arg>(inputs.size() + 1);
            cvArgs.addAll(inputs);
            if (ForeignFunctions.isCommutative(this.functionName)) {
              // put in canonical order
              Collections.sort(cvArgs);
            }
            cvArgs.add(Arg.createIntLit(output)); // Disambiguate outputs
            
            res.add(ValLoc.buildResult(this.op, 
                canonicalFunctionName, cvArgs, 
                getOutput(output).asArg(), outputClosed, IsAssign.TO_LOCATION));
          }
          addSpecialCVs(res);
          return res;
        }
      }
      return null;
    }

    /**
     * Add specific CVs for special operations
     * @param res
     */
    private void addSpecialCVs(List<ValLoc> cvs) {
      if (isImpl(SpecialFunction.INPUT_FILE) ||
          isImpl(SpecialFunction.UNCACHED_INPUT_FILE) ||
          isImpl(SpecialFunction.INPUT_URL)) {
        // Track that the output variable has the filename of the input
        // This is compatible with UNCACHED_INPUT_FILE preventing caching,
        // as we still assume that the input_file function is impure
        if (op == Opcode.CALL_FOREIGN) {
          cvs.add(ValLoc.makeFilename(getInput(0), getOutput(0)));
        } else if (op == Opcode.CALL_FOREIGN_LOCAL){
          // Don't mark as IsAssign since standard cv catches this
          cvs.add(ValLoc.makeFilenameLocal(getInput(0), getOutput(0),
                                        IsAssign.NO));
        }
      } else if (op == Opcode.CALL_FOREIGN_LOCAL &&
          (isImpl(SpecialFunction.RANGE) ||
           isImpl(SpecialFunction.RANGE_STEP))) {
        addRangeCVs(cvs);
      } else if (isImpl(SpecialFunction.SIZE)) {
          cvs.add(makeContainerSizeCV(IsAssign.NO));
      }
    }

    /**
     * @return true if this instruction calls any of the given special functions
     */
    public boolean isImpl(SpecialFunction ...specials) {
      return isImpl(this.functionName, specials);
    }
    
    public static boolean isImpl(String functionName,
                                 SpecialFunction ...specials) {
      for (SpecialFunction special: specials) {
        if (ForeignFunctions.isSpecialImpl(functionName, special)) {
          return true;
        }
      }
      return false;
    }

    private void addRangeCVs(List<ValLoc> cvs) {
      boolean allValues = true;
      long start = 0, end = 0, step = 1; 
      
      if (getInput(0).isIntVal()) {
        start = getInput(0).getIntLit();
      } else {
        allValues = false;
      }
      if (getInput(1).isIntVal()) {
        end = getInput(1).getIntLit();
      } else {
        allValues = false;
      }
      if (isImpl(SpecialFunction.RANGE_STEP)) {
        if (getInput(2).isIntVal()) {
          step = getInput(2).getIntLit();
        } else {
          allValues = false;
        }
      }
      if (allValues) {
        // We can work out array contents 
        long arrSize = Math.max(0, (end - start) / step + 1);
        Var arr = getOutput(0);
        cvs.add(makeContainerSizeCV(arr, Arg.createIntLit(arrSize),
                                false, IsAssign.NO));
        // add array elements up to some limit
        int max_elems = 64;
        for (int i = 0; i <= (end - start) && i < max_elems; i++) {
          // TODO: can't represent value_of(A[i]) 
        }
      }
    }

    private ValLoc makeContainerSizeCV(IsAssign isAssign) {
      boolean isFuture;
      if (Types.isInt(getOutput(0))) {
        isFuture = true;
      } else {
        assert(Types.isIntVal(getOutput(0)));
        isFuture = false;
      }
      return makeContainerSizeCV(getInput(0).getVar(), getOutput(0).asArg(),
                             isFuture, isAssign);
    }

    static ValLoc makeContainerSizeCV(Var arr, Arg size, boolean future,
                                  IsAssign isAssign) {
      assert(Types.isContainer(arr) ||
             Types.isContainerLocal(arr)) : arr;
      assert((!future && size.isImmediateInt()) ||
             (future && Types.isInt(size.type())));
      String subop = future ? ComputedValue.ARRAY_SIZE_FUTURE :
                              ComputedValue.ARRAY_SIZE_VAL;
      return ValLoc.buildResult(Opcode.FAKE, subop,
              arr.asArg(), size, Closed.MAYBE_NOT, isAssign);
    }


    /**
     * Check if we should try to constant fold. To enable constant
     * folding for a funciton it needs ot have an entry here and
     * in tryConstantFold()
     * @param cv
     * @return
     */
    public static boolean canConstantFold(ComputedValue<?> cv) {
      return isImpl((String)cv.subop(), SpecialFunction.ARGV);
    }
    
    /**
     * Try to constant fold any special functions.
     * @param cv
     * @param inputs
     * @return a value arg if successful, null if not
     */
    public static Arg tryConstantFold(ComputedValue<?> cv, List<Arg> inputs) {
      String functionName = (String)cv.subop();
      if (isImpl(functionName, SpecialFunction.ARGV)) {
        Arg argName = inputs.get(0);
        if (argName.isStringVal()) {
          String val = CompileTimeArgs.lookup(argName.getStringLit());
          if (val != null) {
            // Success!
            return Arg.createStringLit(val);
          }
        }
      }
      return null;
    }

    @Override
    public void renameVars(Map<Var, Arg> renames, RenameMode mode) {
      if (mode == RenameMode.REPLACE_VAR || mode == RenameMode.REFERENCE) {
        ICUtil.replaceVarsInList(renames, outputs, false);
      }
      ICUtil.replaceArgsInList(renames, inputs, false);
      if (props != null) {
        ICUtil.replaceArgValsInMap(renames, props);
      }
    }
    
    private boolean treatUpdInputsAsOutputs() {
      return hasUpdateableInputs && RefCounting.WRITABLE_UPDATEABLE_INARGS;
    }

    @Override
    public Pair<List<Var>, List<Var>> getIncrVars(Map<String, Function> functions) {
      switch (op) { 
        case CALL_FOREIGN:
        case CALL_FOREIGN_LOCAL:
        case CALL_CONTROL:
        case CALL_LOCAL:
        case CALL_LOCAL_CONTROL: {
          List<Var> readIncr = new ArrayList<Var>();
          List<Var> writeIncr = new ArrayList<Var>();
          for (Arg inArg: inputs) {
            if (inArg.isVar()) {
              Var inVar = inArg.getVar();
              if (RefCounting.hasReadRefCount(inVar)) {
                readIncr.add(inVar);
              }
              if (Types.isScalarUpdateable(inVar) &&
                  treatUpdInputsAsOutputs()) {
                writeIncr.add(inVar);
              }
            }
          }
          for (int i = 0; i < outputs.size(); i++) {
            Var outVar = outputs.get(i);
            if (RefCounting.hasWriteRefCount(outVar)) {
              writeIncr.add(outVar);
            }
            boolean readRC = false;
            if (op != Opcode.CALL_FOREIGN &&
                op != Opcode.CALL_FOREIGN_LOCAL) {              
              Function f = functions.get(this.functionName);
              boolean writeOnly = f.isOutputWriteOnly(i);
              
              // keep read references to output vars
              if (!writeOnly && RefCounting.hasReadRefCount(outVar)) {
                readRC = true;
              }
            }
            if (readRC && RefCounting.hasReadRefCount(outVar)) {
              readIncr.add(outVar);
            }
          }
          return Pair.create(readIncr, writeIncr);
        }
        case CALL_SYNC:
          // Sync calls must acquire their own references
          return super.getIncrVars();
        default:
          throw new STCRuntimeError("Unexpected function type: " + op);
      }
    }
  }
  
  public static class FunctionCall extends CommonFunctionCall {
    private final List<Boolean> closedInputs; // which inputs are closed
  
    private FunctionCall(Opcode op, String functionName,
        List<Var> outputs, List<Arg> inputs, TaskProps props) {
      super(op, functionName, outputs, inputs, props);
      if (op != Opcode.CALL_FOREIGN && op != Opcode.CALL_CONTROL &&
          op != Opcode.CALL_SYNC && op != Opcode.CALL_LOCAL &&
          op != Opcode.CALL_LOCAL_CONTROL) {
        throw new STCRuntimeError("Tried to create function call"
            + " instruction with invalid opcode");
      }
      this.closedInputs = new ArrayList<Boolean>(inputs.size());
      for (int i = 0; i < inputs.size(); i++) {
        this.closedInputs.add(false);
      }
      assert(props != null);
      
      for(Var v: outputs) {
        assert(v != null);
      }
    }
    
    public static FunctionCall createFunctionCall(
        String functionName, List<Var> outputs, List<Arg> inputs,
        TaskMode mode, TaskProps props) {
      Opcode op;
      if (mode == TaskMode.SYNC) {
        op = Opcode.CALL_SYNC;
      } else if (mode == TaskMode.CONTROL) {
        op = Opcode.CALL_CONTROL;
      } else if (mode == TaskMode.LOCAL) {
        op = Opcode.CALL_LOCAL;
      } else if (mode == TaskMode.LOCAL_CONTROL) {
        op = Opcode.CALL_LOCAL_CONTROL;
      } else {
        throw new STCRuntimeError("Task mode " + mode + " not yet supported");
      }
      return new FunctionCall(op, functionName, outputs, inputs, props);
    }
  
    public static FunctionCall createBuiltinCall(
        String functionName, List<Var> outputs, List<Arg> inputs,
        TaskProps props) {
      return new FunctionCall(Opcode.CALL_FOREIGN, functionName,
          outputs, inputs, props);
    }
  
    @Override
    public String toString() {
      String result = op.toString().toLowerCase() + " " + functionName;
      result += " [";
      for (Var v: outputs) {
        result += " " + v.name();
      }
      result += " ] [";
      for (Arg v: inputs) {
        result += " " + v.toString();
      }
      result += " ]";
      
      result += ICUtil.prettyPrintProps(props);
      
      result += " closed=" + closedInputs;
      return result;
    }
  
    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      switch(this.op) {
      case CALL_FOREIGN:
        gen.builtinFunctionCall(functionName, inputs, outputs, props);
        break;
      case CALL_SYNC:
      case CALL_CONTROL:
      case CALL_LOCAL:
      case CALL_LOCAL_CONTROL:
        TaskMode mode;
        if (op == Opcode.CALL_CONTROL) {
          mode = TaskMode.CONTROL;
        } else if (op == Opcode.CALL_SYNC) {
          mode = TaskMode.SYNC;
        } else if (op == Opcode.CALL_LOCAL) {
          mode = TaskMode.LOCAL;
        } else if (op == Opcode.CALL_LOCAL_CONTROL) {
          mode = TaskMode.LOCAL;
        } else {
          throw new STCRuntimeError("Unexpected op " + op);
        }
        List<Boolean> blocking = info.getBlockingInputVector(functionName);
        assert(blocking != null && blocking.size() == inputs.size()) :
          this + "; blocking: " + blocking;
        List<Boolean> needToBlock = new ArrayList<Boolean>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
          needToBlock.add(blocking.get(i) && (!this.closedInputs.get(i)));
        }
                           
        gen.functionCall(functionName, inputs, outputs, needToBlock,
                                            mode, props);
        break;
      default:
        throw new STCRuntimeError("Huh?");
      }
    }
 
    @Override
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars,
                                           boolean waitForClose) {
      // See which arguments are closed
      boolean allClosed = true;
      if (!waitForClose) {
        for (int i = 0; i < this.inputs.size(); i++) {
          Arg in = this.inputs.get(i);
          if (in.isVar()) {
            if (closedVars.contains(in.getVar())) {
              this.closedInputs.set(i, true);
            } else {
              allClosed = false;
            }
          }
        }
      }
      
      // Deal with mapped variables, which are effectively side-channels
      for (int i = 0; i < this.outputs.size(); i++) {
        Var out = this.outputs.get(i);
        if (Types.isFile(out)) {
          // Need to wait for filename, unless unmapped
          if (!(waitForClose || Semantics.outputMappingAvail(closedVars, out))) {
            allClosed = false;
          }
        }
      }
      
      if (allClosed && (ForeignFunctions.hasOpEquiv(this.functionName)
                || ForeignFunctions.hasInlineVersion(this.functionName))) {
        TaskMode mode = ForeignFunctions.getTaskMode(this.functionName);
        if (mode == null) {
          mode = TaskMode.LOCAL;
        }
        
        // True unless the function alters mapping itself
        boolean mapOutVars = true;
        if (isImpl(SpecialFunction.INITS_OUTPUT_MAPPING)) {
          mapOutVars = false;
        }
        
        // All args are closed!
        return new MakeImmRequest(
            Collections.unmodifiableList(this.outputs),
            Collections.unmodifiableList(this.varInputs(true)),
            mode, false, mapOutVars);

      }
      return null;
    }
    
    @Override
    public MakeImmChange makeImmediate(VarCreator creator,
                                       List<Fetched<Var>> outVars, 
                                       List<Fetched<Arg>> values) {
      // Discard non-future inputs.  These are things like priorities or
      // targets which do not need to be retained for the local version
      List<Var> retainedInputs = varInputs(true);
      assert(values.size() == retainedInputs.size());

      Instruction inst;
      List<Arg> fetchedVals = Fetched.getFetched(values);
      if (ForeignFunctions.hasOpEquiv(functionName)) {
        BuiltinOpcode newOp = ForeignFunctions.getOpEquiv(functionName);
        assert(newOp != null);
        
        if (outputs.size() == 1) {
          checkSwappedOutput(outputs.get(0), outVars.get(0).fetched);
          inst = Builtin.createLocal(newOp, outVars.get(0).fetched,
                                                       fetchedVals);
        } else {
          assert(outputs.size() == 0);
          inst = Builtin.createLocal(newOp, null, fetchedVals);
        }
      } else {
        assert(ForeignFunctions.hasInlineVersion(functionName));
        for (int i = 0; i < outputs.size(); i++) {
          assert(outputs.get(i).equals(outVars.get(i).original));
          checkSwappedOutput(outputs.get(i), outVars.get(i).fetched);
        }
        List<Var> fetchedOut = Fetched.getFetched(outVars);
        inst = new LocalFunctionCall(functionName, fetchedVals, fetchedOut);
      }
      return new MakeImmChange(inst);
    }

    /**
     * Check that old output type was swapped correctly for
     * making immediate
     * @param oldOut
     * @param newOut
     */
    private void checkSwappedOutput(Var oldOut, Var newOut) {
      assert(Types.derefResultType(oldOut.type()).equals(
             newOut.type()));
    }

    @Override
    public List<Var> getBlockingInputs(Program prog) {
      List<Var> blocksOn = new ArrayList<Var>();
      if (op == Opcode.CALL_FOREIGN) {
        for (Arg in: inputs) {
          if (in.isVar()) {
            Var v = in.getVar();
            if (Types.isPrimFuture(v.type())
                || Types.isRef(v.type())) {
              // TODO: this is a conservative idea of which ones are set
              blocksOn.add(v);
            }
          }
        }
      } else if (op == Opcode.CALL_SYNC) {
        // Can't block because we need to enter the function immediately
        return Var.NONE;
      } else if (op == Opcode.CALL_CONTROL ) {
        Function f = prog.lookupFunction(this.functionName);
        
        List<Boolean> blocking = f.getBlockingInputVector();
        List<Var> blockingVars = new ArrayList<Var>();
        assert(blocking.size() == inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
          if (blocking.get(i)) {
            // Add the input. This input must be a variable as
            // otherwise we couldn't be blocking on it
            blockingVars.add(inputs.get(i).getVar());
          }
        }
        return blockingVars;
      }
      return blocksOn;
    }
    
      
    @Override
    public TaskMode getMode() {
      switch (op) {
        case CALL_SYNC:
          return TaskMode.SYNC;
        case CALL_LOCAL:
          return TaskMode.LOCAL;
        case CALL_LOCAL_CONTROL:
          return TaskMode.LOCAL_CONTROL;
        case CALL_FOREIGN:
          return ForeignFunctions.getTaskMode(functionName);
        case CALL_CONTROL:
          return TaskMode.CONTROL;
        default:
          throw new STCRuntimeError("Unexpected function call opcode: " + op);
      }
    }

    @Override
    public Instruction clone() {
      // Variables are immutable so just need to clone lists
      return new FunctionCall(op, functionName, 
          new ArrayList<Var>(outputs), new ArrayList<Arg>(inputs),
          props.clone());
    }
  }
  
  public static class LocalFunctionCall extends CommonFunctionCall {
  
    public LocalFunctionCall(String functionName,
        List<Arg> inputs, List<Var> outputs) {
      super(Opcode.CALL_FOREIGN_LOCAL, functionName, 
            outputs, inputs, null);
      for(Var v: outputs) {
        assert(v != null);
      }
      
      for(Arg a: inputs) {
        assert(a != null);
      }
    }
  
    @Override
    public String toString() {
      return formatFunctionCall(op, functionName, outputs, inputs);
    }
  
    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      gen.builtinLocalFunctionCall(functionName, inputs, outputs);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public List<Pair<Var, InitType>> getInitialized() {
      if (isImpl(SpecialFunction.INITS_OUTPUT_MAPPING)) {
        // The local version of input_file initializes the output for writing
        return Arrays.asList(Pair.create(getOutput(0), InitType.FULL));
      }
      return Collections.emptyList();
    }
    
    @Override
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars,
                                           boolean waitForClose) {
      return null; // already immediate
    }

    @Override
    public List<Var> getBlockingInputs(Program prog) {
      // doesn't take futures as args
      return Var.NONE;
    }

    @Override
    public TaskMode getMode() {
      return TaskMode.SYNC;
    }
    
    @Override
    public List<Var> getClosedOutputs() {
      if (isImpl(SpecialFunction.RANGE) ||
          isImpl(SpecialFunction.RANGE_STEP)) {
        // Range closes outputs at end
        return Arrays.asList(outputs.get(0));
      }
      return super.getClosedOutputs();
    }

    @Override
    public Instruction clone() {
      // Variables are immutable so just need to clone lists
      return new LocalFunctionCall(functionName, 
          new ArrayList<Arg>(inputs), new ArrayList<Var>(outputs));
    }
  }
  
  public static class RunExternal extends Instruction {
    private final String cmd;
    private final ArrayList<Arg> inFiles;
    private final ArrayList<Var> outFiles;
    private final ArrayList<Arg> args;
    private final Redirects<Arg> redirects;
    private final boolean hasSideEffects;
    private final boolean deterministic;
    
    public RunExternal(String cmd, List<Arg> inFiles, List<Var> outFiles, 
               List<Arg> args, Redirects<Arg> redirects,
               boolean hasSideEffects, boolean deterministic) {
      super(Opcode.RUN_EXTERNAL);
      this.cmd = cmd;
      this.inFiles = new ArrayList<Arg>(inFiles);
      this.outFiles = new ArrayList<Var>(outFiles);
      this.args = new ArrayList<Arg>(args);
      this.redirects = redirects.clone();
      this.deterministic = deterministic;
      this.hasSideEffects = hasSideEffects;
    }

    @Override
    public void renameVars(Map<Var, Arg> renames, RenameMode mode) {
      ICUtil.replaceArgsInList(renames, args);
      ICUtil.replaceArgsInList(renames, inFiles);
      redirects.stdin = ICUtil.replaceArg(renames, redirects.stdin, true);
      redirects.stdout = ICUtil.replaceArg(renames, redirects.stdout, true);
      redirects.stderr = ICUtil.replaceArg(renames, redirects.stderr, true);
      if (mode == RenameMode.REFERENCE || mode == RenameMode.REPLACE_VAR) {
        ICUtil.replaceVarsInList(renames, outFiles, false);
      }
    }

    @Override
    public String toString() {
      StringBuilder res = new StringBuilder();
      res.append(formatFunctionCall(op, cmd, outFiles, args));
      String redirectString = redirects.toString();
      if (redirectString.length() > 0) {
        res.append(" " + redirectString);
      }
      res.append(" infiles=[");
      ICUtil.prettyPrintArgList(res, inFiles);
      res.append("]");
      
      res.append(" outfiles=[");
      ICUtil.prettyPrintVarList(res, outFiles);
      res.append("]");
      return res.toString();
    }

    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      gen.runExternal(cmd, args, inFiles, outFiles, 
                  redirects, hasSideEffects, deterministic);
    }

    @Override
    public List<Arg> getInputs() {
      ArrayList<Arg> res = new ArrayList<Arg>();
      res.addAll(args);
      res.addAll(inFiles);
      for (Arg redirFilename: redirects.redirections(true, true)) {
        if (redirFilename != null) {
          res.add(redirFilename);
        }
      }
      return res;
    }

    @Override
    public List<Var> getOutputs() {
      return Collections.unmodifiableList(outFiles);
    }

    @Override
    public boolean hasSideEffects() {
      return hasSideEffects;
    }

    @Override
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars,
                                           boolean waitForClose) {
      // Don't support reducing this
      return null;
    }

    @Override
    public MakeImmChange makeImmediate(VarCreator creator,
                                       List<Fetched<Var>> outVals,
                                       List<Fetched<Arg>> inValues) {
      // Already immediate
      return null;
    }

    @Override
    public List<Var> getBlockingInputs(Program prog) {
      // This instruction runs immediately: we won't actually block on any inputs
      
      // However, the compiler should act as if we depend on input file vars
      return ICUtil.extractVars(inFiles);
    }

    @Override
    public TaskMode getMode() {
      return TaskMode.SYNC;
    }
    
    @Override
    public List<Var> getClosedOutputs() {
      return getOutputs();
    }

    @Override
    public List<ValLoc> getResults() {
      if (deterministic) {
        List<ValLoc> cvs = new ArrayList<ValLoc>(outFiles.size());
        for (int i = 0; i < outFiles.size(); i++) {
          List<Arg> cvArgs = new ArrayList<Arg>(args.size() + 1);
          cvArgs.addAll(args);
          cvArgs.add(Arg.createIntLit(i)); // Disambiguate outputs
          // Unique key for cv includes number of output
          // Output file should be closed after external program executes
          ValLoc cv = ValLoc.buildResult(op, cmd,
                     cvArgs, outFiles.get(i).asArg(), Closed.YES_NOT_RECURSIVE,
                     IsAssign.TO_LOCATION);
          cvs.add(cv);
        }
        return cvs;
      } else {
        return null;
      }
    }

    @Override
    public Instruction clone() {
      return new RunExternal(cmd, inFiles, outFiles,
              args, redirects, hasSideEffects, deterministic);
    }
    
  }
  
  public static class LoopContinue extends Instruction {
    private final ArrayList<Arg> newLoopVars;
    private final ArrayList<Var> loopUsedVars;
    private final ArrayList<Boolean> blockingVars;
    private final ArrayList<Boolean> closedVars;

    public LoopContinue(List<Arg> newLoopVars, 
                        List<Var> loopUsedVars,
                        List<Boolean> blockingVars,
                        List<Boolean> closedVars) {
      super(Opcode.LOOP_CONTINUE);
      this.newLoopVars = new ArrayList<Arg>(newLoopVars);
      this.loopUsedVars = new ArrayList<Var>(loopUsedVars);
      this.blockingVars = new ArrayList<Boolean>(blockingVars);
      this.closedVars = new ArrayList<Boolean>(closedVars);
    }
    

    public LoopContinue(List<Arg> newLoopVars, 
                        List<Var> loopUsedVars,
                        List<Boolean> blockingVars) {
      this(newLoopVars, loopUsedVars, blockingVars,
           initClosedVars(newLoopVars.size(), false));
    }

    private static List<Boolean> initClosedVars(int length, boolean val) {
      ArrayList<Boolean> res = new ArrayList<Boolean>(length);
      for (int i = 0; i < length; i++) {
        res.add(val);
      }
      return res;
    }

    public void setNewLoopVar(int index, Arg newVal) {
      this.newLoopVars.set(index, newVal);
    }


    public void setBlocking(int i, boolean b) {
      this.blockingVars.set(i, b);
    }
    
    @Override
    public void renameVars(Map<Var, Arg> renames, RenameMode mode) {
      ICUtil.replaceArgsInList(renames, newLoopVars, false);
      if (mode == RenameMode.REFERENCE || mode == RenameMode.REPLACE_VAR) {
        ICUtil.replaceVarsInList(renames, loopUsedVars, true);
      }
    }

    @Override
    public void removeVars(Set<Var> removeVars) {
      assert(!removeVars.contains(newLoopVars.get(0)));
      loopUsedVars.removeAll(removeVars);
      newLoopVars.removeAll(removeVars);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(this.op.toString().toLowerCase());
      sb.append(" [");
      ICUtil.prettyPrintArgList(sb, this.newLoopVars);
      sb.append("] #passin[");
      ICUtil.prettyPrintVarList(sb, this.loopUsedVars);
      sb.append("] #blocking[");
      ICUtil.prettyPrintList(sb, this.blockingVars);
      sb.append("] #closed[");
      ICUtil.prettyPrintList(sb, this.closedVars);
      sb.append("]");
      return sb.toString();
    }
  
    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      List<Boolean> waitFor = new ArrayList<Boolean>(this.blockingVars.size());
      // See if we need to block on all inputs
      Set<Var> alreadySeen = new HashSet<Var>();
      
      for (int i = 0; i < this.blockingVars.size(); i++) {
        // Add those that we need to wait for and that aren't closed
        Arg initVal = this.newLoopVars.get(i);
        boolean mustWait = initVal.isVar() && this.blockingVars.get(i)
                                           && !this.closedVars.get(i);
        boolean newMustWait = mustWait && !alreadySeen.contains(initVal.getVar());
        waitFor.add(newMustWait);
        if (newMustWait) {
          alreadySeen.add(initVal.getVar());
        }
      }
      gen.loopContinue(this.newLoopVars, this.loopUsedVars, waitFor);
    }
  
    public Arg getNewLoopVar(int i) {
      return newLoopVars.get(i);
    }
    
    @Override
    public List<Arg> getInputs() {
      // need to make sure that these variables are avail in scope
      ArrayList<Arg> res = new ArrayList<Arg>(newLoopVars.size());
      for (Arg v: newLoopVars) {
        res.add(v);
      }
      
      for (Var uv: loopUsedVars) {
        Arg uva = uv.asArg();
        if (!res.contains(uva)) {
          res.add(uva);
        }
      }
      return res;
    }
  
    @Override
    public List<Var> getOutputs() {
      // No outputs
      return new ArrayList<Var>(0);
    }
  
    @Override
    public boolean hasSideEffects() {
      return true;
    }

    @Override
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars,
                                           boolean waitForClose) {
      // Variables we need to wait for to make immediate
      List<Var> waitForInputs = new ArrayList<Var>();
      
      for (int i = 0; i < this.newLoopVars.size(); i++) {
        Arg v = this.newLoopVars.get(i);
        if (v.isConstant() || closedVars.contains(v.getVar())) {
          // Mark as closed
          this.closedVars.set(i, true);
        }
        
        if (this.blockingVars.get(i)) {
          if (this.closedVars.get(i)) {
            // TODO: if we were actually changing instruction,
            //      could request value here.  Since we're not changing,
            //      requesting value and doing nothing with it would result
            //      in infinite loop
          } else if (waitForClose && v.isVar()) {
              // Would be nice to have closed
              waitForInputs.add(v.getVar());
          } 
        }
      }
      
      // TODO: not actually changing instruction - only change if
      //      there are additional things we want to wait for
      if (waitForInputs.isEmpty()) {
        return null;
      } else {
        return new MakeImmRequest(
            Var.NONE, waitForInputs,
            TaskMode.LOCAL, false, false);
      }
    }
    
    @Override
    public MakeImmChange makeImmediate(VarCreator creator,
        List<Fetched<Var>> outVals,
        List<Fetched<Arg>> inValues) {
      // TODO: we waited for vars, for now don't actually change instruction
      return new MakeImmChange(this);
    }

    @Override
    public List<Var> getReadIncrVars() {
      // Increment variables passed to next iter
      return Collections.unmodifiableList(ICUtil.extractVars(newLoopVars));
    }

    @Override
    public List<Var> getBlockingInputs(Program prog) {
      return Var.NONE;
    }
    

    public boolean isLoopVarClosed(int i) {
      return closedVars.get(i);
    }


    public void setLoopVarClosed(int i, boolean value) {
      closedVars.set(i, value);
    }


    @Override
    public TaskMode getMode() {
      return TaskMode.CONTROL;
    }
    
    public void setLoopUsedVars(Collection<Var> variables) {
      loopUsedVars.clear();
      loopUsedVars.addAll(variables);
    }
    
    @Override
    public List<ValLoc> getResults() {
      // Nothing
      return null;
    }

    @Override
    public Instruction clone() {
      return new LoopContinue(newLoopVars, loopUsedVars,
                              blockingVars, closedVars);
    }

  }
  
  public static class LoopBreak extends Instruction {
    /**
     * Variables where refcount should be decremented upon loop termination
     */
    private final ArrayList<PassedVar> loopUsedVars;

    /**
     * Variables to be closed upon loop termination
     */
    private final ArrayList<Var> keepOpenVars;
  
    public LoopBreak(List<PassedVar> loopUsedVars, List<Var> keepOpenVars) {
      super(Opcode.LOOP_BREAK);
      this.loopUsedVars = new ArrayList<PassedVar>(loopUsedVars);
      this.keepOpenVars = new ArrayList<Var>(keepOpenVars);
    }
  
    @Override
    public void renameVars(Map<Var, Arg> renames, RenameMode mode) {
      // do nothing
    }

    public List<PassedVar> getLoopUsedVars() {
      return Collections.unmodifiableList(loopUsedVars);
    }
    
    public List<Var> getKeepOpenVars() {
      return Collections.unmodifiableList(keepOpenVars);
    }
    
    public void setLoopUsedVars(Collection<PassedVar> passedVars) {
      this.loopUsedVars.clear();
      this.loopUsedVars.addAll(passedVars);
    }

    public void setKeepOpenVars(Collection<Var> keepOpen) {
      this.keepOpenVars.clear();
      this.keepOpenVars.addAll(keepOpen);
    }

    
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(this.op.toString().toLowerCase());

      sb.append(" #passin[");
      ICUtil.prettyPrintList(sb, this.loopUsedVars);

      sb.append("] #keepopen[");
      ICUtil.prettyPrintVarList(sb, this.keepOpenVars);
      sb.append(']');
      return sb.toString();
    }
  
    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      gen.loopBreak(PassedVar.extractVars(loopUsedVars), keepOpenVars);
    }
  
    @Override
    public List<Arg> getInputs() {
      return new ArrayList<Arg>(0);
    }
  
    @Override
    public List<Var> getOutputs() {
      return new ArrayList<Var>(0);
    }
  
    @Override
    public boolean hasSideEffects() {
      return true;
    }

    @Override
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars,
                                           boolean waitForClose) {
      return null;
    }
   
    @Override
    public List<Var> getBlockingInputs(Program prog) {
      return Var.NONE;
    }

    @Override
    public TaskMode getMode() {
      return TaskMode.SYNC;
    }
   
    @Override
    public List<ValLoc> getResults() {
      // nothing
      return null;
    }

    @Override
    public Instruction clone() {
      return new LoopBreak(loopUsedVars, keepOpenVars);
    }
  }
  
  /**
   * Builtin operation.  Depending on the opcode (LOCAL_OP or ASYNC_OP),
   * it applied to and returns local value variables or futures.
   * Constructors are private, use factory methods to create.
   */
  public static class Builtin extends Instruction {
    public final BuiltinOpcode subop;
    
    private Var output; // null if no output
    private List<Arg> inputs;
    private final TaskProps props; // only defined for async

    private Builtin(Opcode op, BuiltinOpcode subop, Var output, 
          List<Arg> inputs, TaskProps props) {
      super(op);
      if (op == Opcode.LOCAL_OP) {
        assert(props == null);
      } else {
        assert(op == Opcode.ASYNC_OP);
        assert(props != null);
      }
      this.subop = subop;
      this.output = output;
      this.inputs = new ArrayList<Arg>(inputs);
      this.props = props;
    }
    

    @Override
    public String shortOpName() {
      return op.toString().toLowerCase() + "-" + subop.toString().toLowerCase();
    }
    
    public static Builtin createLocal(BuiltinOpcode subop, Var output, 
        Arg input) {
      return new Builtin(Opcode.LOCAL_OP, subop, output, Arrays.asList(input),
                          null);
    }
    
    public static Builtin createLocal(BuiltinOpcode subop, Var output, 
        List<Arg> inputs) {
      return new Builtin(Opcode.LOCAL_OP, subop, output, inputs, null);
    }
    
    public static Builtin createAsync(BuiltinOpcode subop, Var output, 
        List<Arg> inputs, TaskProps props) {
      return new Builtin(Opcode.ASYNC_OP, subop, output, inputs, props);
    }

    public static Builtin createAsync(BuiltinOpcode subop, Var output, 
        List<Arg> inputs) {
      return createAsync(subop, output, inputs, new TaskProps());
    }


    @Override
    public void renameVars(Map<Var, Arg> renames, RenameMode mode) {
      if (mode == RenameMode.REFERENCE || mode == RenameMode.REPLACE_VAR) {
        if (output != null && renames.containsKey(this.output)) {
          this.output = renames.get(this.output).getVar();
        }
      }
      ICUtil.replaceArgsInList(renames, inputs);
      if (props != null) {
        ICUtil.replaceArgValsInMap(renames, props);
      }
      
      // After we replace values, see if we can check assert
      if (op == Opcode.LOCAL_OP && 
          (this.subop == BuiltinOpcode.ASSERT || 
          this.subop == BuiltinOpcode.ASSERT_EQ)) {
        // TODO: get enclosing function name
        compileTimeAssertCheck(subop, this.inputs, "...");
      }
    }

    @Override
    public String toString() {
      String res = op.toString().toLowerCase() + " ";
      if (output != null) {
        res +=  output.name() + " = ";
      }
      res += subop.toString().toLowerCase();
      for (Arg input: inputs) {
        res += " " + input.toString();
      }
      if (props != null) {
        res += ICUtil.prettyPrintProps(props);
      }
      return res;
    }

    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      if (op == Opcode.LOCAL_OP) {
        assert(props == null);
        gen.localOp(subop, output, inputs);
      } else {
        assert (op == Opcode.ASYNC_OP);
        gen.asyncOp(subop, output, inputs, props);
      }
    }

    @Override
    public List<Arg> getInputs() {
      if (props == null) {
        return Collections.unmodifiableList(inputs);
      } else {
        // Need to add priority so that e.g. it doesn't get optimised out
        ArrayList<Arg> res = new ArrayList<Arg>(inputs.size() + 1);
        res.addAll(inputs);
        res.addAll(props.values());
        return res;
      }
    }

    @Override
    public List<Var> getOutputs() {
      if (output != null) {
        return Arrays.asList(output);
      } else {
        return new ArrayList<Var>(0);
      }
    }
    
    @Override
    public boolean hasSideEffects() {
      return Operators.isImpure(subop);
    }

    private static void compileTimeAssertCheck(BuiltinOpcode subop2,
        List<Arg> inputs, String enclosingFnName) {
      
      List<Arg> inputVals = new ArrayList<Arg>(inputs.size());
      // Check that all inputs are available
      for (Arg input: inputs) {
        if (input.isConstant()) {
          inputVals.add(input);
        } else {
          // Can't check
          return;
        }
      }
      
      
      if (subop2 == BuiltinOpcode.ASSERT) {
        Arg cond = inputVals.get(0);
        
        assert(cond.isBoolVal());
        if(!cond.getBoolLit()) {
          compileTimeAssertWarn(enclosingFnName, 
              "constant condition evaluated to false", inputs.get(1));
        }
      } else {
        assert(subop2 == BuiltinOpcode.ASSERT_EQ);
        
        Arg a1 = inputVals.get(0);
        Arg a2 = inputVals.get(1);
        assert(a1.isConstant()) : a1 + " " + a1.getKind();
        assert(a2.isConstant()) : a2 + " " + a2.getKind();
        if (a1 != null && a2 != null) {
          if(!a1.equals(a2)) {
            String reason = a1.toString() + " != " + a2.toString();
            Arg msg = inputVals.get(2);
            compileTimeAssertWarn(enclosingFnName, reason, msg);
          }
        }
      }
    }

    private static void compileTimeAssertWarn(String enclosingFnName,
        String reason, Arg assertMsg) {
      String errMessage;
      if (assertMsg.isConstant()) {
        errMessage = assertMsg.getStringLit();
      } else {
        errMessage = "<RUNTIME ERROR MESSAGE>";
      }
        
      System.err.println("Warning: assertion in " + enclosingFnName +
          " with error message: \"" + errMessage + 
          "\" will fail at runtime because " + reason + "\n"
          + "This may be a compiler internal error: check your code" +
              " and report if this warning is faulty");
    }

    private static boolean hasLocalVersion(BuiltinOpcode op) {
      return true;
    }

    @Override
    public MakeImmRequest canMakeImmediate(Set<Var> closedVars,
                                           boolean waitForClose) {
      if (op == Opcode.LOCAL_OP) {
        // already is immediate
        return null; 
      } else { 
        assert(op == Opcode.ASYNC_OP);
        if (!hasLocalVersion(subop)) {
          return null;
        }
        
        // COPY_FILE wants to initialize its own output file
        boolean mapOutputVars = true;
        
        // See which arguments are closed
        if (!waitForClose) {
          for (Arg inarg: this.inputs) {
            assert(inarg.isVar());
            Var in = inarg.getVar();
            if (!closedVars.contains(in)) {
              // Non-closed arg
              return null;
            }
          }
        }
        
        if (Types.isFile(output) && !mapOutputVars) {
          // Need to wait for filename, unless unmapped
          if (!(waitForClose ||
                Semantics.outputMappingAvail(closedVars, output))) {
            return null;
          }
        }
      
          // All args are closed!
        return new MakeImmRequest(
            (this.output == null) ? 
                  null : Collections.singletonList(this.output),
            ICUtil.extractVars(this.inputs),
            TaskMode.LOCAL, false, mapOutputVars);
      }
    }

    @Override
    public MakeImmChange makeImmediate(VarCreator creator,
                                       List<Fetched<Var>> newOut,
                                       List<Fetched<Arg>> newIn) {
      if (op == Opcode.LOCAL_OP) {
        throw new STCRuntimeError("Already immediate!");
      } else {
        assert(newIn.size() == inputs.size());
        List<Arg> newInArgs = Fetched.getFetched(newIn);
        if (output != null) {
          assert(newOut.size() == 1);
          assert(Types.derefResultType(output.type()).equals(
                 newOut.get(0).fetched.type()));
          return new MakeImmChange(
              Builtin.createLocal(subop, newOut.get(0).fetched, newInArgs));
        } else {
          assert(newOut == null || newOut.size() == 0);
          return new MakeImmChange(
              Builtin.createLocal(subop, null, newInArgs));
        }
      }
    }

    @Override
    public List<Var> getBlockingInputs(Program prog) {
      if (op == Opcode.LOCAL_OP) {
        // doesn't take futures as args
        return Var.NONE;
      } else {
        assert(op == Opcode.ASYNC_OP);
        // blocks on all scalar inputs
        ArrayList<Var> result = new ArrayList<Var>();
        for (Arg inarg: inputs) {
          if (inarg.isVar()) {
            Var invar = inarg.getVar();
            if (Types.isRef(invar) || Types.isPrimFuture(invar)) {
              result.add(invar);
            }
          }
        }
        return result;
      }
    }
    @Override
    public TaskMode getMode() {
      if (op == Opcode.ASYNC_OP) {
        return TaskMode.CONTROL;
      } else {
        return TaskMode.SYNC;
      }
    }
    
    @Override
    public List<ValLoc> getResults() {
      if (this.hasSideEffects()) {
        // Two invocations of this aren't equivalent
        return null;
      }
      
      ValLoc basic = makeBasicComputedValue();
      if (basic != null) {
        return basic.asList();
      } else {
        return null;
      }
    }

    /**
     * Create computed value that describes the output
     * @return
     */
    private ValLoc makeBasicComputedValue() {
      if (Operators.isCopy(this.subop)) {
        // It might be assigning a constant val
        return ValLoc.makeCopy(this.output, this.inputs.get(0),
                               IsAssign.TO_LOCATION);
      } else if (Operators.isMinMaxOp(subop)) {
        assert(this.inputs.size() == 2);
        if (this.inputs.get(0).equals(this.inputs.get(1))) {
          return ValLoc.makeCopy(this.output, this.inputs.get(0),
                                 IsAssign.TO_LOCATION);
        }
      } else if (output != null) {
        // put arguments into canonical order
        List<Arg> cvInputs;
        BuiltinOpcode cvOp;
        if (Operators.isCommutative(subop)) {
          cvInputs = new ArrayList<Arg>(this.inputs);
          Collections.sort(cvInputs);
          cvOp = subop;
        } else if (Operators.isFlippable(subop)) {
          cvInputs = new ArrayList<Arg>(this.inputs);
          Collections.reverse(cvInputs);
          cvOp = Operators.flippedOp(subop);
        } else {
          cvInputs = this.inputs;
          cvOp = subop;
        }
        
        return ValLoc.buildResult(this.op, cvOp, cvInputs,
                                this.output.asArg(), outputClosed(op),
                                IsAssign.TO_LOCATION);
      }
      return null;
    }

    private static Closed outputClosed(Opcode op) {
      Closed locClosed = op == Opcode.LOCAL_OP ? Closed.YES_NOT_RECURSIVE : Closed.MAYBE_NOT;
      return locClosed;
    }


    @Override
    public List<Var> getReadIncrVars() {
      if (op == Opcode.ASYNC_OP) {
        List<Var> res = new ArrayList<Var>(inputs.size());
        for (Arg in: inputs) {
          if (RefCounting.hasReadRefCount(in.getVar())) {
            res.add(in.getVar());
          }
        }
        return res;
      }
      return Var.NONE;
    }


    @Override
    public Instruction clone() {
      TaskProps propsClone = props == null ? null : props.clone();
      return new Builtin(op, subop, output, Arg.cloneList(inputs), propsClone);
    }
  }

  public static Instruction valueSet(Var dst, Arg value) {
    if (Types.isPrimValue(dst.type())) {
      switch (dst.type().primType()) {
      case BOOL:
        assert(value.isImmediateBool());
        return Builtin.createLocal(BuiltinOpcode.COPY_BOOL, dst, value);
      case INT:
        assert(value.isImmediateInt());
        return Builtin.createLocal(BuiltinOpcode.COPY_INT, dst, value);
      case FLOAT:
        assert(value.isImmediateFloat());
        return Builtin.createLocal(BuiltinOpcode.COPY_FLOAT, dst, value);
      case STRING:
        assert(value.isImmediateString());
        return Builtin.createLocal(BuiltinOpcode.COPY_STRING, dst, value);
      case BLOB:
        assert(value.isImmediateBlob());
        return Builtin.createLocal(BuiltinOpcode.COPY_BLOB, dst, value);
      case VOID:
        assert(Types.isBoolVal(value.type()));
        return Builtin.createLocal(BuiltinOpcode.COPY_VOID, dst, value);
      default:
        // fall through
        break;
      }
    } else if (Types.isArray(dst.type()) || Types.isStruct(dst.type())) {
      assert(dst.storage() == Alloc.ALIAS);
      assert (value.isVar());
      return TurbineOp.copyRef(dst, value.getVar());
    }

    throw new STCRuntimeError("Unhandled case in valueSet: "
        + " assign " + value.toString() + " to " + dst.toString());
  }

  public static Instruction retrievePrim(Var dst, Var src) {
    assert(Types.isPrimValue(dst));
    assert(Types.isPrimFuture(src));
    if (Types.isScalarFuture(src)) {
      return TurbineOp.retrieveScalar(dst, src);
    } else if (Types.isFile(src)) {
      return TurbineOp.retrieveFile(dst, src);
    } else {
      throw new STCRuntimeError("method to retrieve " +
            src.type().typeName() + " is not known yet");
    }
  }
  
  private static String formatFunctionCall(Opcode op, 
      String functionName, List<Var> outputs, List<Arg> inputs) {
    String result = op.toString().toLowerCase() + " " + functionName;
    result += " [";
    for (Var v: outputs) {
      result += " " + v.name();
    }
    result += " ] [";
    for (Arg a: inputs) {
      result += " " + a.toString();
    }
    result += " ]";
    return result;
  }
  
}
