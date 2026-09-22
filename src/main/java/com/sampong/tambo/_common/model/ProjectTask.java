package com.sampong.tambo._common.model;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A runnable project task. Only mise has a task runner today, so vfox returns none of these —
 * but the shape is backend-neutral so the Tasks surface renders from one type either way,
 * showing {@code BackendFeature.TASKS}'s unsupported reason instead of vanishing.
 *
 * @param name        the task name as the CLI expects it
 * @param aliases     other names that run the same task
 * @param description one line about the task, when the config declares one
 * @param source      the config file that defines it
 * @param depends     tasks that run first
 * @param run         the shell commands the task executes
 */
public record ProjectTask(
        String name,
        List<String> aliases,
        @Nullable String description,
        @Nullable String source,
        List<String> depends,
        List<String> run
) {

    public String aliasSummary() {
        return aliases.isEmpty() ? "-" : String.join(", ", aliases);
    }

    public String dependsSummary() {
        return depends.isEmpty() ? "-" : String.join(", ", depends);
    }

    public String runSummary() {
        return run.isEmpty() ? "-" : String.join("  &&  ", run);
    }
}
