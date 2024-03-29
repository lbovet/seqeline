package ch.post.tools.seqeline.metadata;

import ch.post.tools.seqeline.binding.Binding;
import ch.post.tools.seqeline.binding.BindingType;
import lombok.Getter;

@Getter
public class Relation {

    private Relation() {};

    public static Relation of(String name, RelationType type, String comment) {
        var result = new Relation();
        result.binding = new Binding(name, BindingType.RELATION);
        result.binding.setComment(comment);
        result.type = type;
        result.binding.addType(type.toString());
        return result;
    }

    private Binding binding;
    private RelationType type;
}
