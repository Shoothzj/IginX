package cn.edu.tsinghua.iginx.engine.physical.task;

import cn.edu.tsinghua.iginx.engine.physical.exception.PhysicalException;
import cn.edu.tsinghua.iginx.engine.physical.exception.PhysicalTaskExecuteFailureException;
import cn.edu.tsinghua.iginx.engine.physical.memory.execute.naive.NaiveOperatorMemoryExecutor;
import cn.edu.tsinghua.iginx.engine.shared.data.read.RowStream;
import cn.edu.tsinghua.iginx.engine.shared.operator.MultipleOperator;
import cn.edu.tsinghua.iginx.engine.shared.operator.Operator;
import cn.edu.tsinghua.iginx.engine.shared.operator.OperatorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 目前专门用于 CombineNonQuery 和 SetCombine 操作符
 */
public class MultipleMemoryPhysicalTask extends MemoryPhysicalTask {

    private static final Logger logger = LoggerFactory.getLogger(MultipleMemoryPhysicalTask.class);

    private final List<PhysicalTask> parentTasks;

    public MultipleMemoryPhysicalTask(List<Operator> operators, List<PhysicalTask> parentTasks) {
        super(TaskType.MultipleMemory, operators);
        this.parentTasks = parentTasks;
    }

    public List<PhysicalTask> getParentTasks() {
        return parentTasks;
    }

    @Override
    public TaskExecuteResult execute() {
        List<Operator> operators = getOperators();
        if (operators.size() != 1) {
            return new TaskExecuteResult(new PhysicalException("unexpected multiple memory physical task"));
        }
        Operator operator = operators.get(0);
        if (OperatorType.isMultipleOperator(operator.getType())) {
            return new TaskExecuteResult(new PhysicalException("unexpected multiple memory physical task"));
        }
        if (getFollowerTask() != null) {
            return new TaskExecuteResult(new PhysicalException("multiple memory physical task shouldn't have follower task"));
        }
        List<PhysicalException> exceptions = new ArrayList<>();
        for (PhysicalTask parentTask: parentTasks) {
            PhysicalException exception = parentTask.getResult().getException();
            if (exception != null) {
                exceptions.add(exception);
            }
        }
        if (exceptions.size() != 0) {
            StringBuilder message = new StringBuilder("some sub-task execute failure, details: ");
            for (PhysicalException exception: exceptions) {
                message.append(exception.getMessage());
            }
            return new TaskExecuteResult(new PhysicalTaskExecuteFailureException(message.toString()));
        }
        if (operator.getType() == OperatorType.SetCombine) {
            try {
                RowStream stream = NaiveOperatorMemoryExecutor.getInstance().executeMultiOperator((MultipleOperator) operator, parentTasks.stream().map(PhysicalTask::getResult).map(TaskExecuteResult::getRowStream).collect(Collectors.toList()));
                return new TaskExecuteResult(stream);
            } catch (PhysicalException e) {
                logger.error("execute set combine error: ", e);
                return new TaskExecuteResult(e);
            }
        }
        return new TaskExecuteResult();
    }

    @Override
    public boolean notifyParentReady() {
        return parentReadyCount.incrementAndGet() == parentTasks.size();
    }
}