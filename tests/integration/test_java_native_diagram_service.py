import os
import pathlib
import pytest

try:
    import requests  # type: ignore
except Exception:  # pragma: no cover
    requests = None


@pytest.mark.integration
def test_java_service_health_and_create_diagram():
    if requests is None:
        pytest.skip("requests not installed")

    base = os.environ.get("JAVA_DIAGRAM_SERVICE", "http://localhost:8080/api/v1")

    # Health check
    try:
        r = requests.get(f"{base}/health", timeout=2)
    except Exception:
        pytest.skip("Java diagram service not running on localhost:8080")

    assert r.status_code == 200
    data = r.json()
    assert data.get("status") == "UP"

    # Use the repo's GMHCD model for a basic LAB diagram
    model_path = str(pathlib.Path("test_gmhcd_copy/GMHCD.aird").absolute())
    assert os.path.exists(model_path), "GMHCD.aird not found in test_gmhcd_copy/"

    payload = {
        "name": "TDD LAB",
        "type": "LAB",
        "layer": "LA",
        "modelPath": model_path,
        "initialElements": []
    }

    cr = requests.post(f"{base}/diagrams", json=payload, timeout=10)
    assert cr.status_code in (201, 200), cr.text
    body = cr.json()
    assert body.get("diagramId"), body
    assert body.get("layer") == "LA"
    assert body.get("type") == "LAB"

    # Update elements (best-effort reveal + registry bookkeeping)
    did = body["diagramId"]
    ur = requests.put(
        f"{base}/diagrams/{did}/elements",
        json={"add": ["dummy-uuid-1", "dummy-uuid-2"]},
        timeout=10,
    )
    assert ur.status_code == 200, ur.text
    updated = ur.json()
    assert updated.get("elementCount", 0) >= 2

    # List and then delete
    lr = requests.get(f"{base}/diagrams", timeout=5)
    assert lr.status_code == 200
    items = lr.json()
    assert isinstance(items, list)

    dr = requests.delete(f"{base}/diagrams/{did}", timeout=5)
    assert dr.status_code in (200, 204), dr.text
