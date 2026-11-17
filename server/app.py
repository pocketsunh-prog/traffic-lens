"""
Traffic Lens — companion inference server.

This is the Python half of the reference project (Python + OpenCV + YOLO + Flask): it
runs YOLOv8 on a camera or video file, publishes an annotated MJPEG stream, and exposes
the aggregate counters the Android app renders.

Endpoints
---------
GET /video_feed   multipart/x-mixed-replace JPEG stream, annotated with detection boxes
GET /stats        JSON counters, recent deltas and a 15-minute per-minute flow history
GET /health       liveness probe

Run
---
    pip install -r requirements.txt
    python app.py --source 0 --model yolov8n.pt --port 5000

Use `--synthetic` to run without a camera (the server draws a moving road scene itself).
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from collections import deque
from dataclasses import dataclass
from typing import Deque, Dict, Iterable, List, Optional, Tuple

import cv2
from flask import Flask, Response, jsonify

try:  # YOLO is optional so the server can still be used as a synthetic stream.
    from ultralytics import YOLO
except Exception:  # pragma: no cover - depends on the local environment
    YOLO = None


VEHICLE_CLASSES = {2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}
CLASS_GROUPS = {"car": "cars", "truck": "trucks", "bus": "buses", "motorcycle": "motorcycles"}
BOX_COLORS = {
    "car": (240, 205, 90),
    "truck": (90, 160, 250),
    "bus": (150, 220, 100),
    "motorcycle": (250, 155, 170),
}
TREND_MINUTES = 16
DELTA_SECONDS = 20.0


# ---------------------------------------------------------------------------------------------
# Tracking / telemetry
# ---------------------------------------------------------------------------------------------


@dataclass
class Track:
    track_id: int
    label: str
    center: Tuple[float, float]
    last_seen: float
    counted: bool = False


class CentroidTracker:
    """Small centroid tracker: enough to avoid counting the same car every frame."""

    def __init__(self, max_distance: float = 0.12, ttl: float = 1.2) -> None:
        self.max_distance = max_distance
        self.ttl = ttl
        self._tracks: Dict[int, Track] = {}
        self._next_id = 1

    def update(self, detections: Iterable[Tuple[str, Tuple[float, float]]], now: float) -> List[Track]:
        unmatched = list(self._tracks.values())
        for label, center in detections:
            best: Optional[Track] = None
            best_distance = self.max_distance
            for track in unmatched:
                distance = ((track.center[0] - center[0]) ** 2 + (track.center[1] - center[1]) ** 2) ** 0.5
                if distance < best_distance and track.label == label:
                    best = track
                    best_distance = distance
            if best is not None:
                best.center = center
                best.last_seen = now
                unmatched.remove(best)
            else:
                track = Track(self._next_id, label, center, now)
                self._next_id += 1
                self._tracks[track.track_id] = track

        expired = [track for track in self._tracks.values() if now - track.last_seen > self.ttl]
        for track in expired:
            del self._tracks[track.track_id]
        return expired


class TrafficState:
    """Thread-safe counters, minute buckets and the latest encoded frame."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counts = {"cars": 0, "trucks": 0, "buses": 0, "motorcycles": 0}
        self._events: Deque[Tuple[float, str]] = deque()
        self._buckets: Deque[Dict[str, int]] = deque(maxlen=TREND_MINUTES)
        self._current = self._empty_bucket()
        self._bucket_started = time.time()
        self._frame: Optional[bytes] = None
        self._fps = 0.0

    @staticmethod
    def _empty_bucket() -> Dict[str, int]:
        return {"cars": 0, "trucks": 0, "buses": 0, "motorcycles": 0}

    def record(self, label: str) -> None:
        group = CLASS_GROUPS.get(label)
        if group is None:
            return
        now = time.time()
        with self._lock:
            self._counts[group] += 1
            self._current[group] += 1
            self._events.append((now, group))
            self._rotate(now)

    def set_frame(self, jpeg: bytes, fps: float) -> None:
        with self._lock:
            self._frame = jpeg
            self._fps = fps

    def frame(self) -> Optional[bytes]:
        with self._lock:
            return self._frame

    def _rotate(self, now: float) -> None:
        while now - self._bucket_started >= 60.0:
            self._buckets.append(self._current)
            self._current = self._empty_bucket()
            self._bucket_started += 60.0

    def stats(self) -> dict:
        now = time.time()
        with self._lock:
            self._rotate(now)
            window = sum(self._counts.values())
            while self._events and now - self._events[0][0] > DELTA_SECONDS:
                self._events.popleft()
            delta = self._empty_bucket()
            for _, group in self._events:
                delta[group] += 1

            trend: List[Dict[str, int]] = [dict(bucket) for bucket in self._buckets]
            trend.append(dict(self._current))
            while len(trend) < TREND_MINUTES:
                trend.insert(0, self._empty_bucket())

            return {
                "total": window,
                "cars": self._counts["cars"],
                "trucks": self._counts["trucks"],
                "buses": self._counts["buses"],
                "motorcycles": self._counts["motorcycles"],
                "delta": {
                    "cars": delta["cars"],
                    "trucks": delta["trucks"],
                    "buses": delta["buses"],
                    "motorcycles": delta["motorcycles"],
                },
                "fps": round(self._fps, 1),
                "trend": trend[-TREND_MINUTES:],
                "updated": now,
            }


# ---------------------------------------------------------------------------------------------
# Inference
# ---------------------------------------------------------------------------------------------


def annotate(frame, boxes) -> None:
    for (x1, y1, x2, y2, label, confidence) in boxes:
        color = BOX_COLORS.get(label, (255, 255, 255))
        cv2.rectangle(frame, (x1, y1), (x2, y2), color, 2)
        caption = f"{label} {confidence:.2f}"
        (tw, th), _ = cv2.getTextSize(caption, cv2.FONT_HERSHEY_SIMPLEX, 0.42, 1)
        cv2.rectangle(frame, (x1, max(0, y1 - th - 6)), (x1 + tw + 8, y1), color, -1)
        cv2.putText(
            frame,
            caption,
            (x1 + 4, max(10, y1 - 4)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.42,
            (10, 10, 16),
            1,
            cv2.LINE_AA,
        )


def synthetic_scene(width: int, height: int, tick: float):
    """Fallback video source: a simple road with moving blocks, no camera required."""
    frame = cv2.UMat(height, width, cv2.CV_8UC3).get()
    frame[:] = (24, 20, 18)
    horizon = int(height * 0.42)
    cv2.rectangle(frame, (0, 0), (width, horizon), (52, 38, 26), -1)
    for index in range(9):
        left = int(width * (0.05 + index * 0.11))
        cv2.rectangle(frame, (left, horizon - 60 - (index % 3) * 25), (left + 55, horizon), (30, 24, 20), -1)
    cv2.rectangle(frame, (0, horizon), (width, height), (48, 44, 40), -1)
    for lane in range(1, 4):
        x = int(width * lane / 4)
        for dash in range(8):
            y0 = int(horizon + (height - horizon) * (dash / 8) ** 1.6)
            y1 = int(horizon + (height - horizon) * ((dash + 0.45) / 8) ** 1.6)
            cv2.line(frame, (x, y0), (x, y1), (170, 170, 165), 2)

    boxes = []
    for lane in range(4):
        speed = 0.14 + lane * 0.03
        progress = (tick * speed + lane * 0.27) % 1.0
        depth = progress ** 1.5
        center_x = int(width * (0.14 + lane * 0.24))
        base_y = int(horizon + (height - horizon) * depth)
        size = int(24 + 180 * depth)
        x1 = center_x - size // 2
        y1 = base_y - int(size * 0.55)
        label = ["car", "truck", "bus", "motorcycle"][lane]
        cv2.rectangle(frame, (x1, y1), (x1 + size, base_y), BOX_COLORS[label], -1)
        boxes.append((x1, y1, x1 + size, base_y, label, 0.9))
    return frame, boxes


def inference_loop(args, state: TrafficState) -> None:
    model = None
    if YOLO is not None and not args.no_model:
        model = YOLO(args.model)

    capture = None
    if not args.synthetic:
        capture = cv2.VideoCapture(args.source)
        if not capture.isOpened():
            print(f"[warn] cannot open source {args.source!r}; falling back to synthetic scene")
            capture = None

    tracker = CentroidTracker()
    started = time.time()
    last_logged = 0.0
    frame_index = 0

    while True:
        loop_started = time.time()

        if capture is not None:
            ok, frame = capture.read()
            if not ok:
                time.sleep(0.05)
                continue
        else:
            frame, _ = synthetic_scene(960, 540, time.time() - started)

        height, width = frame.shape[:2]
        detections: List[Tuple[str, Tuple[float, float]]] = []
        drawn = []

        if model is not None:
            results = model.predict(frame, conf=args.confidence, verbose=False, imgsz=args.imgsz)
            for result in results:
                for box in result.boxes:
                    cls_id = int(box.cls[0])
                    label = VEHICLE_CLASSES.get(cls_id)
                    if label is None:
                        continue
                    confidence = float(box.conf[0])
                    x1, y1, x2, y2 = (int(value) for value in box.xyxy[0])
                    detections.append((label, ((x1 + x2) / 2 / width, (y1 + y2) / 2 / height)))
                    drawn.append((x1, y1, x2, y2, label, confidence))

        for track in tracker.update(detections, time.time()):
            state.record(track.label)

        annotate(frame, drawn)
        ok, buffer = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), args.quality])
        if ok:
            elapsed = max(time.time() - loop_started, 1e-3)
            state.set_frame(buffer.tobytes(), 1.0 / elapsed)

        frame_index += 1
        if frame_index % 120 == 0 and time.time() - last_logged > 5:
            last_logged = time.time()
            print(f"[info] {frame_index} frames · {len(detections)} detections")

        if args.synthetic:
            time.sleep(max(0.0, 1 / 30 - (time.time() - loop_started)))


# ---------------------------------------------------------------------------------------------
# HTTP surface
# ---------------------------------------------------------------------------------------------


def create_app(state: TrafficState) -> Flask:
    app = Flask(__name__)

    @app.get("/")
    def index():
        return (
            "<h1>Traffic Lens server</h1>"
            "<p>MJPEG: <a href='/video_feed'>/video_feed</a> · JSON: <a href='/stats'>/stats</a></p>"
        )

    @app.get("/health")
    def health():
        return jsonify({"status": "ok", "frame_ready": state.frame() is not None})

    @app.get("/stats")
    def stats():
        response = jsonify(state.stats())
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/video_feed")
    def video_feed():
        def generate():
            boundary = b"--frame\r\n"
            while True:
                jpeg = state.frame()
                if jpeg:
                    yield (
                        boundary
                        + b"Content-Type: image/jpeg\r\n"
                        + b"Content-Length: "
                        + str(len(jpeg)).encode()
                        + b"\r\n\r\n"
                        + jpeg
                        + b"\r\n"
                    )
                time.sleep(1 / 40)

        return Response(generate(), mimetype="multipart/x-mixed-replace; boundary=frame")

    return app


def main() -> None:
    parser = argparse.ArgumentParser(description="Traffic Lens inference server")
    parser.add_argument("--source", default="0", help="camera index or video path")
    parser.add_argument("--model", default="yolov8n.pt", help="Ultralytics weights")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=5000)
    parser.add_argument("--confidence", type=float, default=0.4)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--quality", type=int, default=80, help="JPEG quality 1-100")
    parser.add_argument("--synthetic", action="store_true", help="ignore the camera, draw a fake road")
    parser.add_argument("--no-model", action="store_true", help="stream frames without YOLO inference")
    args = parser.parse_args()

    if args.source.isdigit():
        args.source = int(args.source)

    state = TrafficState()
    worker = threading.Thread(target=inference_loop, args=(args, state), daemon=True)
    worker.start()

    app = create_app(state)
    print(json.dumps({"event": "listening", "url": f"http://{args.host}:{args.port}"}))
    app.run(host=args.host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()
