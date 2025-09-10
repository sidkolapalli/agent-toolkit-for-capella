package org.polarsys.capella.mcp.service;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.impl.TransactionalEditingDomainImpl;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.business.api.session.factory.SessionFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages Capella model sessions for diagram operations.
 * Handles session lifecycle, resource loading, and transaction management.
 */
@Component
public class CapellaSessionManager {
    
    private static final Logger LOGGER = Logger.getLogger(CapellaSessionManager.class.getName());
    
    // Cache of open sessions by model path
    private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();
    
    // Cache of editing domains by session
    private final Map<Session, TransactionalEditingDomain> editingDomainCache = new ConcurrentHashMap<>();
    
    /**
     * Get or create a session for the given model path.
     * 
     * @param modelPath Path to the .aird file
     * @return Opened session
     * @throws Exception if session cannot be opened
     */
    public Session getSession(String modelPath) throws Exception {
        // Check cache first
        Session cachedSession = sessionCache.get(modelPath);
        if (cachedSession != null && cachedSession.isOpen()) {
            return cachedSession;
        }
        
        // Validate model file exists
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            throw new IllegalArgumentException("Model file not found: " + modelPath);
        }
        
        try {
            LOGGER.info("Opening Capella session for: " + modelPath);
            
            // Create URI for the model
            URI modelUri = URI.createFileURI(modelFile.getAbsolutePath());
            
            // Create session using SessionFactory
            IProgressMonitor monitor = new NullProgressMonitor();
            Session session = SessionFactory.INSTANCE.createSession(modelUri, monitor);
            
            if (session == null) {
                // Fallback: Use SessionManager directly
                session = SessionManager.INSTANCE.getSession(modelUri, monitor);
            }
            
            // Open the session
            if (!session.isOpen()) {
                session.open(monitor);
            }
            
            // Cache the session
            sessionCache.put(modelPath, session);
            
            LOGGER.info("Session opened successfully for: " + modelPath);
            return session;
            
        } catch (Exception e) {
            LOGGER.severe("Failed to open session for " + modelPath + ": " + e.getMessage());
            throw new RuntimeException("Failed to open Capella session", e);
        }
    }
    
    /**
     * Get or create a transactional editing domain for the session.
     * 
     * @param session The Sirius session
     * @return TransactionalEditingDomain for safe model modifications
     */
    public TransactionalEditingDomain getEditingDomain(Session session) {
        // Check cache first
        TransactionalEditingDomain cachedDomain = editingDomainCache.get(session);
        if (cachedDomain != null) {
            return cachedDomain;
        }
        
        // Get or create editing domain
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        
        if (domain == null) {
            // Create new editing domain if not available
            ResourceSet resourceSet = session.getAllSessionResources().isEmpty() 
                ? new ResourceSetImpl() 
                : session.getAllSessionResources().iterator().next().getResourceSet();
                
            if (resourceSet != null) {
                domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resourceSet);
            } else {
                // Fallback: create with new resource set
                domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain();
            }
        }
        
        // Cache the domain
        if (domain != null) {
            editingDomainCache.put(session, domain);
        }
        
        return domain;
    }
    
    /**
     * Close a session and clean up resources.
     * 
     * @param modelPath Path to the model
     */
    public void closeSession(String modelPath) {
        Session session = sessionCache.remove(modelPath);
        if (session != null) {
            editingDomainCache.remove(session);
            
            if (session.isOpen()) {
                try {
                    // Save before closing
                    session.save(new NullProgressMonitor());
                    session.close(new NullProgressMonitor());
                    LOGGER.info("Session closed for: " + modelPath);
                } catch (Exception e) {
                    LOGGER.warning("Error closing session: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Save all changes in a session.
     * 
     * @param session The session to save
     * @throws Exception if save fails
     */
    public void saveSession(Session session) throws Exception {
        if (session != null && session.isOpen()) {
            session.save(new NullProgressMonitor());
            LOGGER.info("Session saved successfully");
        }
    }
    
    /**
     * Close all open sessions (for cleanup).
     */
    public void closeAllSessions() {
        for (String modelPath : sessionCache.keySet()) {
            closeSession(modelPath);
        }
        sessionCache.clear();
        editingDomainCache.clear();
    }
    
    /**
     * Check if a session is available for a model.
     * 
     * @param modelPath Path to the model
     * @return true if session exists and is open
     */
    public boolean hasOpenSession(String modelPath) {
        Session session = sessionCache.get(modelPath);
        return session != null && session.isOpen();
    }
    
    /**
     * Get the resource set for a session.
     * 
     * @param session The session
     * @return ResourceSet or null
     */
    public ResourceSet getResourceSet(Session session) {
        if (session == null) {
            return null;
        }
        
        TransactionalEditingDomain domain = getEditingDomain(session);
        if (domain != null) {
            return domain.getResourceSet();
        }
        
        // Fallback: try to get from session resources
        for (Resource resource : session.getAllSessionResources()) {
            if (resource.getResourceSet() != null) {
                return resource.getResourceSet();
            }
        }
        
        return null;
    }
}