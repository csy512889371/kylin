/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.query.relnode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.calcite.DataContext;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.DateFormat;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.metadata.filter.CompareTupleFilter;
import org.apache.kylin.metadata.filter.TupleFilter;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.JoinDesc;
import org.apache.kylin.metadata.model.JoinsTree;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.realization.IRealization;
import org.apache.kylin.metadata.realization.SQLDigest;
import org.apache.kylin.metadata.realization.SQLDigest.SQLCall;
import org.apache.kylin.metadata.tuple.TupleInfo;
import org.apache.kylin.query.routing.RealizationCheck;
import org.apache.kylin.query.schema.OLAPSchema;
import org.apache.kylin.storage.StorageContext;
import org.apache.kylin.storage.hybrid.HybridInstance;

import com.google.common.collect.Lists;

/**
 */
public class OLAPContext {

    public static final String PRM_ACCEPT_PARTIAL_RESULT = "AcceptPartialResult";
    public static final String PRM_USER_AUTHEN_INFO = "UserAuthenInfo";

    static final ThreadLocal<Map<String, String>> _localPrarameters = new ThreadLocal<Map<String, String>>();

    static final ThreadLocal<Map<Integer, OLAPContext>> _localContexts = new ThreadLocal<Map<Integer, OLAPContext>>();

    public static void setParameters(Map<String, String> parameters) {
        _localPrarameters.set(parameters);
    }

    public static void clearParameter() {
        _localPrarameters.remove();
    }

    public static void registerContext(OLAPContext ctx) {
        if (_localContexts.get() == null) {
            Map<Integer, OLAPContext> contextMap = new HashMap<Integer, OLAPContext>();
            _localContexts.set(contextMap);
        }
        _localContexts.get().put(ctx.id, ctx);
    }

    public static Collection<OLAPContext> getThreadLocalContexts() {
        Map<Integer, OLAPContext> map = _localContexts.get();
        return map == null ? null : map.values();
    }

    public static OLAPContext getThreadLocalContextById(int id) {
        Map<Integer, OLAPContext> map = _localContexts.get();
        return map.get(id);
    }

    public static void clearThreadLocalContexts() {
        _localContexts.remove();
    }

    public OLAPContext(int seq) {
        this.id = seq;
        this.storageContext = new StorageContext(seq);
        this.sortColumns = Lists.newArrayList();
        this.sortOrders = Lists.newArrayList();
        Map<String, String> parameters = _localPrarameters.get();
        if (parameters != null) {
            String acceptPartialResult = parameters.get(PRM_ACCEPT_PARTIAL_RESULT);
            if (acceptPartialResult != null) {
                this.storageContext.setAcceptPartialResult(Boolean.parseBoolean(acceptPartialResult));
            }
            String acceptUserInfo = parameters.get(PRM_USER_AUTHEN_INFO);
            if (null != acceptUserInfo)
                this.olapAuthen.parseUserInfo(acceptUserInfo);
        }
    }

    public final int id;
    public final StorageContext storageContext;

    // query info
    public OLAPSchema olapSchema = null;
    public OLAPTableScan firstTableScan = null; // to be fact table scan except "select * from lookupTable"
    public Set<OLAPTableScan> allTableScans = new HashSet<>();
    public Set<OLAPJoinRel> allOlapJoins = new HashSet<>();
    public Set<MeasureDesc> involvedMeasure = new HashSet<>();
    public TupleInfo returnTupleInfo = null;
    public boolean afterAggregate = false;
    public boolean afterHavingClauseFilter = false;
    public boolean afterLimit = false;
    public boolean limitPrecedesAggr = false;
    public boolean afterJoin = false;
    public boolean hasJoin = false;
    public boolean hasWindow = false;

    // cube metadata
    public IRealization realization;
    public RealizationCheck realizationCheck;
    public boolean fixedModel;

    public Set<TblColRef> allColumns = new HashSet<>();
    public List<TblColRef> groupByColumns = new ArrayList<>();
    public Set<TblColRef> subqueryJoinParticipants = new HashSet<TblColRef>();//subqueryJoinParticipants will be added to groupByColumns(only when other group by co-exists) and allColumns
    public Set<TblColRef> metricsColumns = new HashSet<>();
    public List<FunctionDesc> aggregations = new ArrayList<>(); // storage level measure type, on top of which various sql aggr function may apply
    public List<TblColRef> aggrOutCols = new ArrayList<>(); // aggregation output (inner) columns
    public List<SQLCall> aggrSqlCalls = new ArrayList<>(); // sql level aggregation function call
    public Set<TblColRef> filterColumns = new HashSet<>();
    public TupleFilter filter;
    public TupleFilter havingFilter;
    public List<JoinDesc> joins = new LinkedList<>();
    public JoinsTree joinsTree;
    List<TblColRef> sortColumns;
    List<SQLDigest.OrderEnum> sortOrders;

    // rewrite info
    public Map<String, RelDataType> rewriteFields = new HashMap<>();

    // hive query
    public String sql = "";

    public OLAPAuthentication olapAuthen = new OLAPAuthentication();

    public boolean isSimpleQuery() {
        return (joins.size() == 0) && (groupByColumns.size() == 0) && (aggregations.size() == 0);
    }

    SQLDigest sqlDigest;

    public SQLDigest getSQLDigest() {
        if (sqlDigest == null)
            sqlDigest = new SQLDigest(firstTableScan.getTableName(), allColumns, joins, // model
                    groupByColumns, subqueryJoinParticipants, // group by
                    metricsColumns, aggregations, aggrSqlCalls, // aggregation
                    filterColumns, filter, havingFilter, // filter
                    sortColumns, sortOrders, limitPrecedesAggr, // sort & limit
                    involvedMeasure);
        return sqlDigest;
    }

    public boolean hasPrecalculatedFields() {
        return realization instanceof CubeInstance || realization instanceof HybridInstance;
    }

    public void resetSQLDigest() {
        this.sqlDigest = null;
    }

    public boolean belongToContextTables(TblColRef tblColRef) {
        for (OLAPTableScan olapTableScan : this.allTableScans) {
            if (olapTableScan.getColumnRowType().getAllColumns().contains(tblColRef)) {
                return true;
            }
        }

        return false;
    }

    public void setReturnTupleInfo(RelDataType rowType, ColumnRowType columnRowType) {
        TupleInfo info = new TupleInfo();
        List<RelDataTypeField> fieldList = rowType.getFieldList();
        for (int i = 0; i < fieldList.size(); i++) {
            RelDataTypeField field = fieldList.get(i);
            TblColRef col = columnRowType == null ? null : columnRowType.getColumnByIndex(i);
            info.setField(field.getName(), col, i);
        }
        this.returnTupleInfo = info;
    }

    public void addSort(TblColRef col, SQLDigest.OrderEnum order) {
        if (col != null) {
            sortColumns.add(col);
            sortOrders.add(order);
        }
    }

    public void fixModel(DataModelDesc model, Map<String, String> aliasMap) {
        if (fixedModel)
            return;

        for (OLAPTableScan tableScan : this.allTableScans) {
            tableScan.fixColumnRowTypeWithModel(model, aliasMap);
        }
        fixedModel = true;
    }

    public void unfixModel() {
        if (!fixedModel)
            return;

        for (OLAPTableScan tableScan : this.allTableScans) {
            tableScan.unfixColumnRowTypeWithModel();
        }
        fixedModel = false;
    }
    public void bindVariable(DataContext dataContext) {
        bindVariable(this.filter, dataContext);
    }

    private void bindVariable(TupleFilter filter, DataContext dataContext) {
        if (filter == null) {
            return;
        }

        for (TupleFilter childFilter : filter.getChildren()) {
            bindVariable(childFilter, dataContext);
        }

        if (filter instanceof CompareTupleFilter && dataContext != null) {
            CompareTupleFilter compFilter = (CompareTupleFilter) filter;
            for (Map.Entry<String, Object> entry : compFilter.getVariables().entrySet()) {
                String variable = entry.getKey();
                Object value = dataContext.get(variable);
                if (value != null) {
                    String str = value.toString();
                    if (compFilter.getColumn().getType().isDateTimeFamily())
                        str = String.valueOf(DateFormat.stringToMillis(str));

                    compFilter.bindVariable(variable, str);
                }

            }
        }
    }
    // ============================================================================

    public interface IAccessController {
        public void check(List<OLAPContext> contexts, KylinConfig config) throws IllegalStateException;
    }

}
