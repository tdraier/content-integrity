package org.jahia.modules.contentintegrity.services.checks;

import org.jahia.modules.contentintegrity.api.ContentIntegrityCheck;
import org.jahia.modules.contentintegrity.api.ContentIntegrityErrorList;
import org.jahia.modules.contentintegrity.services.Utils;
import org.jahia.modules.contentintegrity.services.impl.AbstractContentIntegrityCheck;
import org.jahia.modules.contentintegrity.services.impl.JCRUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.Arrays;
import java.util.Objects;

import static org.jahia.modules.contentintegrity.services.impl.Constants.CALCULATION_ERROR;

@Component(service = ContentIntegrityCheck.class, immediate = true)
public class ReferencesSanityCheck extends AbstractContentIntegrityCheck {

    private static final Logger logger = LoggerFactory.getLogger(ReferencesSanityCheck.class);

    private enum ErrorType {INVALID_BACK_REF, BROKEN_REF}

    @Override
    public ContentIntegrityErrorList checkIntegrityBeforeChildren(JCRNodeWrapper node) {
        return Utils.mergeErrorLists(
                checkBackReferences(node),
                checkReferences(node)
        );
    }

    private ContentIntegrityErrorList checkReferences(JCRNodeWrapper node) {
        final PropertyIterator properties = JCRUtils.runJcrCallBack(node, Node::getProperties);
        if (properties == null) return null;

        ContentIntegrityErrorList errors = null;

        while (properties.hasNext()) {
            final Property property = properties.nextProperty();
            try {
                property.getDefinition();
                property.getType();
            } catch (RepositoryException e) {
                logger.error(String.format("Skipping %s as its definition is inconsistent", JCRUtils.runJcrCallBack(property, Item::getPath, CALCULATION_ERROR)), e);
                continue;
            }
            final Boolean isMultiple = JCRUtils.runJcrCallBack(property, Property::isMultiple);
            if (isMultiple == null) continue;

            final ContentIntegrityErrorList propErrors;
            if (isMultiple) {
                final Value[] values = JCRUtils.runJcrCallBack(property, Property::getValues);
                if (values == null) continue;
                propErrors = Arrays.stream(values)
                        .map(v -> checkPropertyValue(v, node, property))
                        .filter(Objects::nonNull)
                        .reduce(Utils::mergeErrorLists)
                        .orElse(null);
            } else {
                final Value value = JCRUtils.runJcrCallBack(property, Property::getValue);
                if (value == null) continue;
                propErrors = checkPropertyValue(value, node, property);
            }
            errors = Utils.mergeErrorLists(errors, propErrors);
        }
        return errors;
    }

    private ContentIntegrityErrorList checkPropertyValue(Value value, JCRNodeWrapper checkedNode, Property property) {
        switch (value.getType()) {
            case PropertyType.REFERENCE:
            case PropertyType.WEAKREFERENCE:
                return JCRUtils.runJcrCallBack(value, v -> {
                    final String uuid = v.getString();
                    if (JCRUtils.nodeExists(uuid, checkedNode.getSession(), true)) return null;
                    return createSingleError(createError(checkedNode, "Broken reference")
                            .setErrorType(ErrorType.BROKEN_REF)
                            .addExtraInfo("property-name", JCRUtils.runJcrCallBack(property, Property::getName, CALCULATION_ERROR))
                            .addExtraInfo("missing-uuid", uuid, true));
                });
            default:
                return null;
        }
    }

    private ContentIntegrityErrorList checkBackReferences(JCRNodeWrapper node) {
        final PropertyIterator weakReferences = JCRUtils.isExternalNode(node) ? null : JCRUtils.runJcrCallBack(node, Node::getWeakReferences);
        final PropertyIterator references = JCRUtils.isExternalNode(node) ? null : JCRUtils.runJcrCallBack(node, Node::getReferences);
        return Utils.mergeErrorLists(
                checkBackReferences(weakReferences, node),
                checkBackReferences(references, node)
        );
    }

    private ContentIntegrityErrorList checkBackReferences(PropertyIterator propertyIterator, JCRNodeWrapper checkedNode) {
        if (propertyIterator == null) return null;

        ContentIntegrityErrorList errors = null;
        while (propertyIterator.hasNext()) {
            final Property property = propertyIterator.nextProperty();
            final String referencingNodeID;
            try {
                referencingNodeID = property.getParent().getIdentifier();
                property.getSession().getNodeByIdentifier(referencingNodeID);
            } catch (RepositoryException e) {
                if (errors == null) errors = createEmptyErrorsList();
                errors.addError(createError(checkedNode, "Missing referencing node")
                        .setErrorType(ErrorType.INVALID_BACK_REF)
                        .addExtraInfo("property-name", JCRUtils.runJcrCallBack(property, Item::getName, CALCULATION_ERROR))
                        .addExtraInfo("referencing-node-path", JCRUtils.runJcrCallBack(property, p -> p.getParent().getPath(), CALCULATION_ERROR), true));
            }
        }
        return errors;
    }
}
