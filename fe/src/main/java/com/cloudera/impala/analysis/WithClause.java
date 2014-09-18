// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.analysis;

import java.util.ArrayList;
import java.util.List;

import com.cloudera.impala.authorization.PrivilegeRequest;
import com.cloudera.impala.catalog.View;
import com.cloudera.impala.common.AnalysisException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Representation of the WITH clause that may appear before a query statement or insert
 * statement. A WITH clause contains a list of named view definitions that may be
 * referenced in the query statement that follows it.
 *
 * Scoping rules:
 * A WITH-clause view is visible inside the query statement that it belongs to.
 * This includes inline views and nested WITH clauses inside the query statement.
 *
 * Each WITH clause establishes a new analysis scope. A WITH-clause view definition
 * may refer to views from the same WITH-clause appearing to its left, and to all
 * WITH-clause views from outer scopes.
 *
 * References to WITH-clause views are resolved inside out, i.e., a match is found by
 * first looking in the current scope and then in the enclosing scope(s).
 *
 * Views defined within the same WITH-clause may not use the same alias.
 */
public class WithClause implements ParseNode {
  private final ArrayList<View> views_;

  public WithClause(ArrayList<View> views) {
    Preconditions.checkNotNull(views);
    Preconditions.checkState(!views.isEmpty());
    views_ = views;
  }

  /**
   * Copy c'tor.
   */
  public WithClause(WithClause other) {
    Preconditions.checkNotNull(other);
    views_ = Lists.newArrayList();
    for (View view: other.views_) {
      views_.add(new View(view.getName(), view.getQueryStmt().clone()));
    }
  }

  /**
   * Analyzes all views and registers them with the analyzer. Enforces scoping rules.
   * All local views registered with the analyzer are have QueryStmts with resolved
   * TableRefs to simplify the analysis of view references.
   */
  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    // Create an analyzer for the WITH clause. If this is the top-level WITH
    // clause, the new analyzer uses its own global state and is not attached to
    // the hierarchy of analyzers. Otherwise, it becomes a child of 'analyzer'
    // to be able to resolve WITH-clause views registered in an ancestor of
    // 'analyzer' (see IMPALA-1106).
    Analyzer withClauseAnalyzer = null;
    if (analyzer.isRootAnalyzer()) {
      withClauseAnalyzer = new Analyzer(analyzer.getCatalog(), analyzer.getQueryCtx(),
          analyzer.getAuthzConfig());
    } else {
      withClauseAnalyzer = new Analyzer(analyzer);
    }
    if (analyzer.isExplain()) withClauseAnalyzer.setIsExplain();
    try {
      for (View view: views_) {
        Analyzer viewAnalyzer = new Analyzer(withClauseAnalyzer);
        view.getQueryStmt().analyze(viewAnalyzer);
        // Register this view so that the next view can reference it.
        withClauseAnalyzer.registerLocalView(view);
      }
      // Register all local views with the analyzer.
      for (View localView: withClauseAnalyzer.getLocalViews().values()) {
        analyzer.registerLocalView(localView);
      }
      // Record audit events because the resolved table references won't generate any
      // when a view is referenced.
      analyzer.getAccessEvents().addAll(withClauseAnalyzer.getAccessEvents());

      // Register all privilege requests made from the root analyzer.
      for (PrivilegeRequest req: withClauseAnalyzer.getPrivilegeReqs()) {
        analyzer.registerPrivReq(req);
      }
    } finally {
      // Record missing tables in the original analyzer.
      if (analyzer.isRootAnalyzer()) {
        analyzer.getMissingTbls().addAll(withClauseAnalyzer.getMissingTbls());
      }
    }
  }

  @Override
  public WithClause clone() { return new WithClause(this); }

  @Override
  public String toSql() {
    List<String> viewStrings = Lists.newArrayList();
    for (View view: views_) {
      // Enclose the view alias in quotes if Hive cannot parse it without quotes.
      // This is needed for view compatibility between Impala and Hive.
      String aliasSql = ToSqlUtils.getIdentSql(view.getName());
      viewStrings.add(aliasSql + " AS (" + view.getQueryStmt().toSql() + ")");
    }
    return "WITH " + Joiner.on(",").join(viewStrings);
  }
}
