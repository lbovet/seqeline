package ch.post.tools.seqeline.process;

import ch.post.tools.seqeline.binding.Binding;
import ch.post.tools.seqeline.binding.BindingType;
import ch.post.tools.seqeline.metadata.Schema;
import ch.post.tools.seqeline.stack.*;
import com.google.common.collect.Streams;
import lombok.RequiredArgsConstructor;
import org.joox.Match;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class NodeProcessor {

    private final Stack stack;
    private final Schema schema;

    public void process(Match node) {
        if(node.isEmpty()) {
            return;
        }

        switch (node.tag()) {
            case "create_package" -> skip();

            case "create_package_body" -> {
                var pack = binding(identifier(node), BindingType.PACKAGE);
                context().declare(pack);
                stack.execute(new LexicalScope(pack), processSiblings(node.child("package_name")));
            }

            case "variable_declaration" -> {
                var variable = binding(identifier(node), BindingType.VARIABLE);
                context().declare(variable);
                stack.execute(new Assignment(variable), processSiblings(node.child(0)));
            }

            case "procedure_body", "function_body" -> {
                var routine = binding(identifier(node), BindingType.ROUTINE);
                context().declare(routine);

                stack.execute(new LexicalScope(routine), () -> {
                    var i = new AtomicInteger(0);
                    node.children("parameter").children("parameter_name").each().stream()
                            .map(param -> binding(identifier(param), BindingType.PARAMETER)
                                    .position(i.getAndIncrement()))
                            .forEach(param -> context().declare(param));
                    processChildren(node.child("seq_of_declare_specs")).run();
                    processChildren(node.child("body")).run();
                });
            }

            case "call_statement" -> stack.execute(new RoutineCall(), () -> {
                // Name
                var qualifiedName = QualifiedName.builder().type(BindingType.ROUTINE);
                var names = node.find("routine_name").find("id_expression");
                if(names.each().size() == 2) {
                    qualifiedName.prefix(text(names.first())).name(text(names.last()));
                } else {
                    qualifiedName.name(text(names.first()));
                }
                context().returnBinding(context().resolve(qualifiedName.build()).orElseThrow());

                // Arguments
                AtomicInteger position = new AtomicInteger(0);
                node.children("argument").each().forEach(argument -> {
                    if(argument.child("identifier").isEmpty()) {
                        stack.execute(new Wrapper(new Binding("["+position+"]", BindingType.ARGUMENT).position(position.getAndIncrement())), processChildren(argument));
                    } else {
                        stack.execute(new Wrapper(binding(identifier(argument), BindingType.ARGUMENT)), processChildren(argument));
                    }
                });
            });

            case "id_expression" -> {
                if(node.next("id_expression").isEmpty()) {
                    var id = resolveNew(node, BindingType.FIELD);
                    context().returnBinding(id);
                    node.prev("id_expression").each().forEach(relation -> context().resolve(QualifiedName.of(text(relation))).ifPresent(r -> r.addChild(id)));
                }
            }

            case "return_statement" ->
                stack.execute(new Wrapper(new Binding("[return]", BindingType.RETURN)), processChildren(node));

            case "cursor_declaration" -> {
                var cursor = binding(identifier(node), BindingType.CURSOR);
                context().declare(cursor);
                stack.execute(new LexicalScope(cursor), () -> {
                    node.children("parameter_spec").children("parameter_name").each().stream()
                            .map(param -> binding(identifier(param), BindingType.PARAMETER))
                            .forEach(param -> context().declare(param));
                    stack.execute(new Wrapper(new Binding("[cursor]", BindingType.RETURN)), ()->process(node.child("select_only_statement")));
                });
            }

            case "fetch_statement" -> {
                var cursorName = node.child("cursor_name");
                var source = resolveExisting(cursorName.find("id_expression").first());
                cursorName.nextAll().find("id_expression").each().stream()
                        .map(this::resolveExisting)
                        .forEach(source::addOutput);
            }

            case "assignment_statement" ->
                stack.execute(new Assignment(), processChildren(node));

            case "if_statement", "simple_case_statement", "simple_case_when_part", "searched_case_when_part" -> {
                // TODO: consider effects
                stack.execute(new IgnoreReturn(), () -> process(node.child(0)));
                processSiblings(node.child(0)).run();
            }

            case "loop_statement" -> stack.execute(new LexicalScope(), () -> {
                // TODO: consider effects
                var loopParam = node.child("cursor_loop_param");
                if(loopParam.child("record_name").isNotEmpty()) {
                    stack.execute(new Assignment(resolveNew(loopParam.child("record_name"), BindingType.STRUCTURE)),
                            () -> process(loopParam.child("cursor_name")));
                }
                process(node.child("seq_of_statements"));
            });

            case "query_block" -> {
                if (node.child("into_clause").isNotEmpty()) {
                    stack.execute(new MultipleAssignment(intoVariables(node)),
                            () -> stack.execute(new SelectStatement(), processChildren(node)));
                } else {
                    stack.execute(new SelectStatement(), processChildren(node));
                }
            }

            case "WithClause" ->
                node.children("Name").each().forEach(child -> {
                    var name = binding(child, BindingType.STRUCTURE);
                    stack.execute(new Children(name), ()->process(child.next()));
                    context().declare(name);
                });

            case "selected_list" ->
                stack.execute(new SelectStatement.SelectList(), () ->
                        node.child("select_list_elements").children().each().forEach(child -> {
                            if (child.is("column_alias")) {
                                Binding alias = binding(child, BindingType.ALIAS);
                                context().declare(alias);
                                context().returnBinding(alias);
                                stack.execute(new Assignment(alias), () -> process(child.prev()));
                            } else {
                                process(child.prev());
                                if (child.next().isEmpty()) {
                                    process(child);
                                }
                            }
                        }));

            case "SingleTableInsert" -> {
                var tableName = text(node.child("InsertIntoClause").find("TableName"));
                var table = stack.root().declare(schema.resolve(tableName).orElse(new Binding(tableName, BindingType.RELATION)));
                Stream<Binding> targets = node.child("InsertIntoClause").find("Column").each().stream()
                        .map(column -> binding(column, BindingType.COLUMN))
                        .map(table::addChild);
                Stream<Match> sources = node.child("ValuesClause").children().each().stream();

                Streams.zip(sources, targets, Map::entry)
                        .forEach(entry -> stack.execute(new Assignment(entry.getValue()), () -> process(entry.getKey())));
            }

            case "tableview_name" -> {
                var struct = schema.resolve(text(identifier(node)))
                        .map(relation -> stack.root().declare(relation))
                        .orElse(resolveNew(node, BindingType.STRUCTURE));
                context().returnBinding(struct);
            }

            case "PrimaryExpression" ->
                stack.execute(new Children(true), processChildren(node));

            case "PrimarySuffix" -> {
                var id = node.child("QualifiedID");
                if(id.isNotEmpty()) {
                    context().returnBinding(binding(id, BindingType.FIELD));
                } else {
                    processChildren(node).run();
                }
            }

            case "JoinClause", "WhereClause" -> {
                stack.execute(new SelectStatement.EffectClause(), processChildren(node));
            }

            case "SubqueryOperation" -> {
                if(node.parent("SelectStatement").isNotEmpty() && node.prevAll("SubqueryOperation").isEmpty()) {
                    stack.pop();
                }
            }

            default -> processChildren(node).run();
        }
    }

    private List<Binding> intoVariables(Match node) {
        var vars = node.child("into_clause").find("id_expression").each();
        return vars.stream()
                .map(this::text)
                .map(QualifiedName::of)
                .map(t -> context().resolve(t).orElseThrow())
                .toList();
    }

    private Binding resolveNew(Match node, BindingType type) {
        return context().resolve(QualifiedName.of(null, text(node), true)).or(() ->
                Optional.of(context().declare(binding(node, type)))).orElseThrow();
    }

    private Binding resolveExisting(Match node) {
        return context().resolve(QualifiedName.of(null, text(node), true)).orElseThrow();
    }

    private Binding binding(Match node, BindingType type) {
        return new Binding(text(node), type);
    }

    private String text(Match node) {
        return node.text().trim().toLowerCase();
    }

    private Match identifier(Match node) {
        return node.find("identifier").first().find("id_expression").last();
    }

    private Runnable processChildren(Match node) {
        return () -> node.children().each().forEach(this::process);
    }

    private Runnable processSiblings(Match node) {
        return () -> node.siblings().each().forEach(this::process);
    }

    private void skip() {
        // do nothing
    }

    private Frame context() {
        return stack.top();
    }
}
