/*******************************************************************************
 * Copyright (c) 2024 AI Capella Integration Project
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *    AI-Generated - Spring Boot application for Capella diagram service
 *******************************************************************************/
package org.polarsys.capella.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot application for Capella Native Diagram Service
 * 
 * Provides REST API endpoints for creating all 8 diagram types 
 * across all 4 ARCADIA layers (32 variants total).
 * 
 * Integrates with Python MCP server for complete AI-Capella integration.
 * 
 * API Endpoints:
 * - POST /api/capella/diagrams/create - Universal diagram creation
 * - POST /api/capella/diagrams/create/oa - OA layer diagrams
 * - POST /api/capella/diagrams/create/sa - SA layer diagrams  
 * - POST /api/capella/diagrams/create/la - LA layer diagrams
 * - POST /api/capella/diagrams/create/pa - PA layer diagrams
 * - POST /api/capella/diagrams/types - Get available diagram types
 * 
 * Health Check:
 * - GET /actuator/health - Service health status
 * 
 * Usage from Python MCP server:
 * ```python
 * import requests
 * 
 * response = requests.post('http://localhost:8080/api/capella/diagrams/create/la', json={
 *     'modelPath': '/path/to/model.aird',
 *     'diagramType': 'ARCHITECTURE_BLANK', 
 *     'diagramName': 'My LA Diagram'
 * })
 * ```
 */
@SpringBootApplication
@ComponentScan(basePackages = "org.polarsys.capella.mcp")
public class CapellaDiagramServiceApplication {

    public static void main(String[] args) {
        System.out.println("Starting Capella Native Diagram Service...");
        System.out.println("Supporting ALL 8 diagram types across ALL 4 ARCADIA layers");
        System.out.println("API Documentation: http://localhost:8080/actuator/health");
        
        SpringApplication.run(CapellaDiagramServiceApplication.class, args);
    }
}