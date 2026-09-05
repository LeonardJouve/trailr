package api

import (
	"bytes"
	"compress/gzip"
	"io"
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

func TestTilesServedWithGzipEncoding(t *testing.T) {
	// tippecanoe writes gzipped pbf; clients must be told via
	// Content-Encoding or MapLibre parses raw gzip bytes as protobuf.
	tilesDir := t.TempDir()

	tileDir := filepath.Join(tilesDir, "8", "134", "90")
	if err := os.MkdirAll(tileDir, 0o755); err != nil {
		t.Fatal(err)
	}

	rawGzip := func(payload string) []byte {
		var buf bytes.Buffer
		w := gzip.NewWriter(&buf)
		if _, err := w.Write([]byte(payload)); err != nil {
			t.Fatal(err)
		}
		if err := w.Close(); err != nil {
			t.Fatal(err)
		}
		return buf.Bytes()
	}

	gzipped := rawGzip("tile-bytes")
	if err := os.WriteFile(filepath.Join(tileDir, "1.pbf"), gzipped, 0o644); err != nil {
		t.Fatal(err)
	}

	ts := httptest.NewServer(newServer(tilesDir))
	defer ts.Close()

	// plain client: ts.Client() disables transparent gzip, which is exactly
	// the behavior under test.
	client := &http.Client{}
	response, err := client.Get(ts.URL + "/tiles/8/134/90/1.pbf")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("default client failed to decode response: %v", err)
	}

	if string(body) != "tile-bytes" {
		t.Fatalf("expected transparently decoded body, got %q", body)
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
	if !strings.Contains(body, `"https://trail.famillejouve.ch/tiles/{z}/{x}/{y}.pbf"`) {
		t.Fatalf(`style must reference tiles with the hardcoded absolute url, got: %s`, body)
	}
	for _, layer := range []string{`"wanderwege"`, `"veloland"`} {
		if !strings.Contains(body, layer) {
			t.Fatalf("style is missing source-layer %s", layer)
		}
	}
}
