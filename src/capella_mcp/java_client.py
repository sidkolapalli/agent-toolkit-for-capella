"""
Java Diagram Service Client
Provides HTTP communication with the Java diagram service for native .aird persistence
"""

import requests
from typing import Dict, List, Optional, Any
import logging
from urllib.parse import urljoin
import time
from requests.adapters import HTTPAdapter
from requests.packages.urllib3.util.retry import Retry

logger = logging.getLogger(__name__)


class JavaDiagramServiceClient:
    """Client for communicating with the Java diagram service"""
    
    def __init__(self, base_url: str = "http://localhost:8080", timeout: int = 30):
        """
        Initialize the Java service client
        
        Args:
            base_url: Base URL of the Java service
            timeout: Request timeout in seconds
        """
        self.base_url = base_url
        self.api_base = f"{base_url}/api/v1"
        self.timeout = timeout
        
        # Configure session with connection pooling and retries
        self.session = requests.Session()
        retry_strategy = Retry(
            total=3,
            backoff_factor=1,
            status_forcelist=[429, 500, 502, 503, 504],
            allowed_methods=["HEAD", "GET", "PUT", "DELETE", "OPTIONS", "TRACE", "POST"]
        )
        adapter = HTTPAdapter(
            max_retries=retry_strategy,
            pool_connections=10,
            pool_maxsize=10
        )
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)
        
        # Cache for service availability
        self._service_available = None
        self._last_health_check = 0
        self._health_check_interval = 60  # seconds
    
    def is_available(self) -> bool:
        """
        Check if the Java service is available
        
        Returns:
            True if service is healthy, False otherwise
        """
        current_time = time.time()
        
        # Use cached result if recent
        if (self._service_available is not None and 
            current_time - self._last_health_check < self._health_check_interval):
            return self._service_available
        
        try:
            response = self.session.get(
                f"{self.api_base}/health",
                timeout=5
            )
            self._service_available = response.status_code == 200
            self._last_health_check = current_time
            
            if self._service_available:
                data = response.json()
                logger.info(f"Java service healthy: {data.get('service', 'Unknown')}")
            
            return self._service_available
            
        except Exception as e:
            logger.warning(f"Java service health check failed: {e}")
            self._service_available = False
            self._last_health_check = current_time
            return False
    
    def create_diagram(
        self,
        name: str,
        diagram_type: str,
        layer: str,
        model_path: str,
        description: Optional[str] = None,
        initial_elements: Optional[List[str]] = None
    ) -> Dict[str, Any]:
        """
        Create a new diagram in the Java service
        
        Args:
            name: Diagram name
            diagram_type: Type of diagram (e.g., "LAB", "SAB")
            layer: ARCADIA layer ("OA", "SA", "LA", "PA")
            model_path: Path to the .aird model file
            description: Optional diagram description
            initial_elements: Optional list of element UUIDs to include
        
        Returns:
            Response containing diagram ID and metadata
        
        Raises:
            requests.RequestException: If the request fails
            ValueError: If the service returns an error
        """
        if not self.is_available():
            raise ConnectionError("Java diagram service is not available")
        
        request_data = {
            "name": name,
            "type": diagram_type,
            "layer": layer,
            "modelPath": model_path
        }
        
        if description:
            request_data["description"] = description
        
        if initial_elements:
            request_data["initialElements"] = initial_elements
        
        try:
            response = self.session.post(
                f"{self.api_base}/diagrams",
                json=request_data,
                timeout=self.timeout
            )
            
            if response.status_code == 201:
                return response.json()
            elif response.status_code == 400:
                error_data = response.json()
                raise ValueError(f"Invalid request: {error_data.get('error', 'Unknown error')}")
            else:
                response.raise_for_status()
                
        except requests.RequestException as e:
            logger.error(f"Failed to create diagram: {e}")
            raise
    
    def get_diagram(self, diagram_id: str) -> Dict[str, Any]:
        """
        Get a diagram by ID
        
        Args:
            diagram_id: UUID of the diagram
        
        Returns:
            Diagram data including elements and metadata
        
        Raises:
            requests.RequestException: If the request fails
            ValueError: If the diagram is not found
        """
        if not self.is_available():
            raise ConnectionError("Java diagram service is not available")
        
        try:
            response = self.session.get(
                f"{self.api_base}/diagrams/{diagram_id}",
                timeout=self.timeout
            )
            
            if response.status_code == 200:
                return response.json()
            elif response.status_code == 404:
                raise ValueError(f"Diagram not found: {diagram_id}")
            else:
                response.raise_for_status()
                
        except requests.RequestException as e:
            logger.error(f"Failed to get diagram {diagram_id}: {e}")
            raise
    
    def list_diagrams(
        self,
        layer: Optional[str] = None,
        diagram_type: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        List all diagrams with optional filtering
        
        Args:
            layer: Filter by ARCADIA layer
            diagram_type: Filter by diagram type
        
        Returns:
            List of diagram summaries
        
        Raises:
            requests.RequestException: If the request fails
        """
        if not self.is_available():
            raise ConnectionError("Java diagram service is not available")
        
        params = {}
        if layer:
            params["layer"] = layer
        if diagram_type:
            params["type"] = diagram_type
        
        try:
            response = self.session.get(
                f"{self.api_base}/diagrams",
                params=params if params else None,
                timeout=self.timeout
            )
            
            if response.status_code == 200:
                return response.json()
            else:
                response.raise_for_status()
                
        except requests.RequestException as e:
            logger.error(f"Failed to list diagrams: {e}")
            raise
    
    def update_diagram_elements(
        self,
        diagram_id: str,
        add_elements: Optional[List[str]] = None,
        remove_elements: Optional[List[str]] = None
    ) -> Dict[str, Any]:
        """
        Update diagram elements
        
        Args:
            diagram_id: UUID of the diagram
            add_elements: Element UUIDs to add
            remove_elements: Element UUIDs to remove
        
        Returns:
            Updated diagram data
        
        Raises:
            requests.RequestException: If the request fails
            ValueError: If the diagram is not found
        """
        if not self.is_available():
            raise ConnectionError("Java diagram service is not available")
        
        if not add_elements and not remove_elements:
            raise ValueError("Must specify elements to add or remove")
        
        request_data = {}
        if add_elements:
            request_data["add"] = add_elements
        if remove_elements:
            request_data["remove"] = remove_elements
        
        try:
            response = self.session.put(
                f"{self.api_base}/diagrams/{diagram_id}/elements",
                json=request_data,
                timeout=self.timeout
            )
            
            if response.status_code == 200:
                return response.json()
            elif response.status_code == 404:
                raise ValueError(f"Diagram not found: {diagram_id}")
            else:
                response.raise_for_status()
                
        except requests.RequestException as e:
            logger.error(f"Failed to update diagram {diagram_id}: {e}")
            raise
    
    def delete_diagram(self, diagram_id: str) -> bool:
        """
        Delete a diagram
        
        Args:
            diagram_id: UUID of the diagram
        
        Returns:
            True if deletion was successful
        
        Raises:
            requests.RequestException: If the request fails
            ValueError: If the diagram is not found
        """
        if not self.is_available():
            raise ConnectionError("Java diagram service is not available")
        
        try:
            response = self.session.delete(
                f"{self.api_base}/diagrams/{diagram_id}",
                timeout=self.timeout
            )
            
            if response.status_code == 204:
                return True
            elif response.status_code == 404:
                raise ValueError(f"Diagram not found: {diagram_id}")
            else:
                response.raise_for_status()
                
        except requests.RequestException as e:
            logger.error(f"Failed to delete diagram {diagram_id}: {e}")
            raise
    
    def close(self):
        """Close the HTTP session"""
        self.session.close()
    
    def __enter__(self):
        """Context manager entry"""
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit"""
        self.close()


# Singleton instance for reuse across MCP tools
_default_client = None


def get_java_client(base_url: str = "http://localhost:8080") -> JavaDiagramServiceClient:
    """
    Get or create the default Java service client
    
    Args:
        base_url: Base URL of the Java service
    
    Returns:
        JavaDiagramServiceClient instance
    """
    global _default_client
    
    if _default_client is None:
        _default_client = JavaDiagramServiceClient(base_url)
    
    return _default_client