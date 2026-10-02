"""Start the existing Forma API with native ingestion and its built dashboard."""

import os

import uvicorn


if __name__ == "__main__":
    uvicorn.run(
        "data_sync.native_app.server.app:app",
        host=os.getenv("NATIVE_HOST", "0.0.0.0"),
        port=int(os.getenv("NATIVE_PORT", "8000")),
    )
