package appeng.libs.mdast.mdx.model;

import appeng.libs.mdast.model.MdAstAnyContent;
import appeng.libs.unist.UnistNode;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface MdxJsxElementFields extends UnistNode {
    @Nullable
    String name();

    void setName(String name);

    List<MdxJsxAttributeNode> attributes();

    List<? extends MdAstAnyContent> children();

    default boolean hasAttribute(String name) {
        for (var attributeNode : attributes()) {
            if (attributeNode instanceof MdxJsxAttribute jsxAttribute) {
                if (name.equals(jsxAttribute.name)) {
                    return true;
                }
            } else if (attributeNode instanceof MdxJsxExpressionAttribute jsxExpressionAttribute) {
                throw new IllegalStateException("Attribute spreads unsupported!");
            }
        }

        return false;
    }

    default String getAttributeString(String name, String defaultValue) {
        var jsxAttribute = getAttribute(name);
        return jsxAttribute != null ? jsxAttribute.getStringValue() : defaultValue;
    }

    default void addAttribute(String name, String value) {
        attributes().add(new MdxJsxAttribute(name, value));
    }

    @Nullable
    default MdxJsxAttribute getAttribute(String name) {
        for (var attributeNode : attributes()) {
            if (attributeNode instanceof MdxJsxAttribute jsxAttribute) {
                if (name.equals(jsxAttribute.name)) {
                    return jsxAttribute;
                }
            } else if (attributeNode instanceof MdxJsxExpressionAttribute jsxExpressionAttribute) {
                throw new IllegalStateException("Attribute spreads unsupported!");
            }
        }

        return null;
    }
}
