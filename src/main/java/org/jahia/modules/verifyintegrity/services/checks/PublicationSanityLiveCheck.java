package org.jahia.modules.verifyintegrity.services.checks;

import org.jahia.modules.verifyintegrity.services.AbstractContentIntegrityCheck;
import org.jahia.modules.verifyintegrity.services.ContentIntegrityError;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;

import static org.jahia.api.Constants.EDIT_WORKSPACE;
import static org.jahia.api.Constants.LIVE_WORKSPACE;
import static org.jahia.api.Constants.ORIGIN_WORKSPACE;

public class PublicationSanityLiveCheck extends AbstractContentIntegrityCheck implements AbstractContentIntegrityCheck.SupportsIntegrityErrorFix {

    private static final Logger logger = LoggerFactory.getLogger(PublicationSanityLiveCheck.class);


    private enum ErrorType {NO_DEFAULT_NODE}

    @Override
    public ContentIntegrityError checkIntegrityBeforeChildren(Node node) {
        try {
            final JCRSessionWrapper defaultSession = JCRSessionFactory.getInstance().getCurrentSystemSession(EDIT_WORKSPACE, null, null);
            if (node.hasProperty(ORIGIN_WORKSPACE) && LIVE_WORKSPACE.equals(node.getProperty(ORIGIN_WORKSPACE).getString())) {
                // UGC
                return null;
            }
            try {
                defaultSession.getNodeByIdentifier(node.getIdentifier());
                return null;
            } catch (ItemNotFoundException infe) {
                final String msg = "Found not-UGC node which exists only in live";
                final ContentIntegrityError error = ContentIntegrityError.createError(node, null, msg, this);
                error.setExtraInfos(ErrorType.NO_DEFAULT_NODE);
                return error;
            }
        } catch (RepositoryException e) {
            logger.error("", e);
            return null;
        }
    }

    @Override
    public ContentIntegrityError checkIntegrityAfterChildren(Node node) {
        return null;
    }


    @Override
    public boolean fixError(Node node, Object errorExtraInfos) throws RepositoryException {
        if (errorExtraInfos == null || !(errorExtraInfos instanceof ErrorType)) {
            logger.error("Unexpected error type: " + errorExtraInfos);
            return false;
        }
        final ErrorType errorType = (ErrorType) errorExtraInfos;
        switch (errorType) {
            case NO_DEFAULT_NODE:
                // We assume here that the deletion has not been correctly published. An alternative fix would be to consider
                // that this node is not correctly flagged as UGC, and so to flag it as such.
                node.remove();
                node.getSession().save();
                return true;
        }
        return false;
    }
}
