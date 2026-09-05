package api

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTilesServedStatically(t *testing.T) {
	tilesDir := t.TempDir()

	tileDir := filepath.Join(tilesDir, "8", "134", "90")
	if err := os.MkdirAll(tileDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(tileDir, "1.pbf"), []byte("tile-bytes"), 0o644); err != nil {
		t.Fatal(err)
	}

	server := newServer(tilesDir)

	request := httptest.NewRequest(http.MethodGet, "/tiles/8/134/90/1.pbf", nil)
	recorder := httptest.NewRecorder()

	server.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", recorder.Code)
	}

	if recorder.Body.String() != "tile-bytes" {
		t.Fatalf("unexpected body: %q", recorder.Body.String())
	}
}

func TestMissingTileReturnsNotFound(t *testing.T) {
	server := newServer(t.TempDir())

	request := httptest.NewRequest(http.MethodGet, "/tiles/8/134/90/1.pbf", nil)
	recorder := httptest.NewRecorder()

	server.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusNotFound {
		t.Fatalf("expected status 404, got %d", recorder.Code)
	}
}

func TestStyleServed(t *testing.T) {
	server := newServer(t.TempDir())

	request := httptest.NewRequest(http.MethodGet, "/style.json", nil)
	recorder := httptest.NewRecorder()

	server.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", recorder.Code)
	}

	body := recorder.Body.String()
	if !strings.Contains(body, `"tiles/{z}/{x}/{y}.pbf"`) {
		t.Fatalf(`style must reference tiles with the relative path "tiles/{z}/{x}/{y}.pbf", got: %s`, body)
	}
	for _, layer := range []string{`"wanderwege"`, `"veloland"`} {
		if !strings.Contains(body, layer) {
			t.Fatalf("style is missing source-layer %s", layer)
		}
	}
}
