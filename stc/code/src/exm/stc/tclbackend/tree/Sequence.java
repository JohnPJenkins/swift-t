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

package exm.stc.tclbackend.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Line after line sequence of Tcl code
 * @author wozniak
 * */
public class Sequence extends TclTree {

  protected final List<TclTree> members = new ArrayList<TclTree>();

  public Sequence() {
    super();
  }

  public Sequence(List<? extends TclTree> elems) {
    this();
    addAll(elems);
  }

  public Sequence(TclTree... elems) {
    this();
    for (TclTree elem: elems) {
      add(elem);
    }
  }

  public void add(TclTree tree) {
    members.add(tree);
  }

  public void addAll(TclTree[] trees) {
    addAll(Arrays.asList(trees));
  }

  public void addAll(List<? extends TclTree> trees) {
    for (TclTree tree: trees) {
      add(tree);
    }
  }

  /**
   * Append at end of current sequence
   * @param seq
   */
  public void append(Sequence seq) {
    members.addAll(seq.members);
  }

  @Override
  public void appendTo(StringBuilder sb) {
    for (TclTree member: members) {
      member.setIndentation(indentation);
      member.appendTo(sb);
    }
  }
}
