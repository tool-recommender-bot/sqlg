package org.umlg.sqlg.strategy;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.umlg.sqlg.sql.parse.ReplacedStep;
import org.umlg.sqlg.sql.parse.ReplacedStepTree;

import java.util.ListIterator;
import java.util.stream.Stream;

/**
 * @author Pieter Martin (https://github.com/pietermartin)
 *         Date: 2017/03/04
 *         <p>
 *         Got tired of TinkerPop's static strategy vibe.
 *         Thank the good Lord for OO.
 */
public class GraphStrategy extends BaseStrategy {

    private Logger logger = LoggerFactory.getLogger(GraphStrategy.class.getName());

    private GraphStrategy(Traversal.Admin<?, ?> traversal) {
        super(traversal);
    }

    public static GraphStrategy from(Traversal.Admin<?, ?> traversal) {
        return new GraphStrategy(traversal);
    }

    void apply() {
        final Step<?, ?> startStep = traversal.getStartStep();

        if (!(startStep instanceof GraphStep)) {
            return;
        }
        final GraphStep originalGraphStep = (GraphStep) startStep;

        if (this.sqlgGraph.features().supportsBatchMode() && this.sqlgGraph.tx().isInNormalBatchMode()) {
            this.sqlgGraph.tx().flush();
        }

        if (originalGraphStep.getIds().length > 0) {
            Object id = originalGraphStep.getIds()[0];
            if (id != null) {
                Class clazz = id.getClass();
                if (!Stream.of(originalGraphStep.getIds()).allMatch(i -> clazz.isAssignableFrom(i.getClass())))
                    throw Graph.Exceptions.idArgsMustBeEitherIdOrElement();
            }
        }
        if (this.canNotBeOptimized()) {
            this.logger.debug("gremlin not optimized due to path or tree step. " + this.traversal.toString() + "\nPath to gremlin:\n" + ExceptionUtils.getStackTrace(new Throwable()));
            return;
        }
        combineSteps();

    }

    @Override
    protected SqlgStep constructSqlgStep(Step startStep) {
        Preconditions.checkArgument(startStep instanceof GraphStep, "Expected a GraphStep, found instead a " + startStep.getClass().getName());
        GraphStep<?, ?> graphStep = (GraphStep) startStep;
        return new SqlgGraphStepCompiled(this.sqlgGraph, this.traversal, graphStep.getReturnClass(), graphStep.isStartStep(), graphStep.getIds());
    }

    @Override
    protected boolean doFirst(ListIterator<Step<?, ?>> stepIterator, Step<?, ?> step, MutableInt pathCount) {
        this.currentReplacedStep = ReplacedStep.from(
                this.currentReplacedStep,
                this.sqlgGraph.getTopology(),
                (AbstractStep<?, ?>) step,
                pathCount.getValue()
        );
        collectHasSteps(stepIterator, pathCount.getValue());
        collectOrderGlobalSteps(stepIterator, pathCount);
        collectRangeGlobalSteps(stepIterator, pathCount);
        this.sqlgStep = constructSqlgStep(step);
        this.currentTreeNodeNode = this.sqlgStep.addReplacedStep(this.currentReplacedStep);
        replaceStepInTraversal(step, this.sqlgStep);
        if (this.sqlgStep instanceof SqlgGraphStepCompiled && ((SqlgGraphStepCompiled) this.sqlgStep).getIds().length > 0) {
            addHasContainerForIds((SqlgGraphStepCompiled) this.sqlgStep);
        }
        if (this.currentReplacedStep.getLabels().isEmpty()) {
            boolean precedesPathStep = precedesPathOrTreeStep(this.traversal);
            if (precedesPathStep) {
                this.currentReplacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_FAKE_LABEL);
            }
        }
        pathCount.increment();
        return true;
    }

    @Override
    protected void doLast() {
        ReplacedStepTree replacedStepTree = this.currentTreeNodeNode.getReplacedStepTree();
        replacedStepTree.maybeAddLabelToLeafNodes();
        //If the order is over multiple tables then the resultSet will be completely loaded into memory and then sorted.
        if (replacedStepTree.hasOrderBy()) {
            ((SqlgGraphStepCompiled) this.sqlgStep).parseForStrategy();
            if (!this.sqlgStep.isForMultipleQueries() && replacedStepTree.orderByIsOrder()) {
                replacedStepTree.applyComparatorsOnDb();
            } else {
                this.sqlgStep.setEagerLoad(true);
            }
        }
        //If a range follows an order that needs to be done in memory then do not apply the range on the db.
        //range is always the last step as sqlg does not optimize beyond a range step.
        if (replacedStepTree.hasRange()) {
            if (replacedStepTree.hasOrderBy()) {
                replacedStepTree.doNotApplyRangeOnDb();
                this.sqlgStep.setEagerLoad(true);
            } else {
                ((SqlgGraphStepCompiled) this.sqlgStep).parseForStrategy();
                if (!this.sqlgStep.isForMultipleQueries()) {
                    //In this case the range is only applied on the db.
                    replacedStepTree.doNotApplyInStep();
                }
            }
        }
        //TODO multiple queries with a range can still apply the range without an offset on the db and then do the final range in the step.
    }

    @Override
    protected boolean isReplaceableStep(Class<? extends Step> stepClass) {
        return CONSECUTIVE_STEPS_TO_REPLACE.contains(stepClass);
    }

    @Override
    protected void replaceStepInTraversal(Step stepToReplace, SqlgStep sqlgStep) {
        TraversalHelper.replaceStep(stepToReplace, sqlgStep, this.traversal);
    }
}
