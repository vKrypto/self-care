import React, { useEffect, useRef, useState } from "react";
import { AlertCircle, LoaderCircle, Sparkles } from "lucide-react";
import { api, labelDate } from "./api";

function FeedbackList({ title, items }) {
  return items?.length ? (
    <div className="feedback-detail">
      <h4>{title}</h4>
      <ul>
        {items.map((item, index) => (
          <li key={index}>{item}</li>
        ))}
      </ul>
    </div>
  ) : null;
}

export default function ProgressFeedback({
  date,
  revision,
  photoCount,
  saving,
}) {
  const [tracking, setTracking] = useState(null);
  const [loading, setLoading] = useState(true);
  const [analyzing, setAnalyzing] = useState(false);
  const [error, setError] = useState("");
  const [analysisError, setAnalysisError] = useState("");
  const [retry, setRetry] = useState(0);
  const requestVersion = useRef(0);

  useEffect(() => {
    const version = ++requestVersion.current;
    const controller = new AbortController();
    setLoading(true);
    setTracking(null);
    setError("");
    setAnalysisError("");
    setAnalyzing(false);
    api("/tracking/" + date, { signal: controller.signal })
      .then((result) => {
        if (version === requestVersion.current && result.date === date)
          setTracking(result);
      })
      .catch((e) => {
        if (!controller.signal.aborted && version === requestVersion.current)
          setError(e.message);
      })
      .finally(() => {
        if (version === requestVersion.current) setLoading(false);
      });
    return () => {
      requestVersion.current++;
      controller.abort();
    };
  }, [date, revision, retry]);

  async function analyze() {
    const version = requestVersion.current;
    setAnalyzing(true);
    setAnalysisError("");
    try {
      const result = await api("/progress/photos/analyze", {
        method: "POST",
        body: { date },
      });
      if (version !== requestVersion.current) return;
      if (result.tracking?.date === date) setTracking(result.tracking);
      else if (result.photo_review?.date === date)
        setTracking((current) => ({
          ...current,
          photo_review: result.photo_review,
        }));
    } catch (e) {
      if (version === requestVersion.current) setAnalysisError(e.message);
    } finally {
      if (version === requestVersion.current) setAnalyzing(false);
    }
  }

  const review = tracking?.photo_review;
  return (
    <section
      className="day-feedback"
      aria-label="Daily progress feedback"
      aria-busy={loading || analyzing}
    >
      <span className="insight-label">
        <Sparkles size={15} /> DAILY FEEDBACK
      </span>
      <h3>
        {labelDate(date, { month: "long", day: "numeric", year: "numeric" })}
      </h3>
      {loading && (
        <p className="feedback-loading" role="status">
          <LoaderCircle className="spin" size={15} /> Updating your progress…
        </p>
      )}
      {error && (
        <div className="feedback-error" role="alert">
          <AlertCircle size={15} />
          <span>{error}</span>
          <button
            type="button"
            className="text-button"
            onClick={() => setRetry((value) => value + 1)}
          >
            Retry
          </button>
        </div>
      )}
      {tracking && (
        <>
          <div className="feedback-counts">
            <b>{tracking.adherence?.completed_percent ?? 0}% complete</b>
            <span>
              {tracking.counts.completed} completed · {tracking.counts.skipped}{" "}
              skipped · {tracking.counts.pending} pending
            </span>
          </div>
          <p>{tracking.feedback?.summary}</p>
          <FeedbackList
            title="Your activity record"
            items={tracking.feedback?.observations}
          />
          <FeedbackList
            title="Next steps"
            items={tracking.feedback?.next_steps}
          />
        </>
      )}
      <div className="photo-analysis">
        <h4>Photo feedback</h4>
        <p>
          Compare this day’s photos with earlier uploads and your saved activity
          record.
        </p>
        <button
          type="button"
          className="outline"
          onClick={analyze}
          disabled={analyzing || loading || saving || !photoCount}
        >
          {analyzing ? (
            <>
              <LoaderCircle className="spin" size={15} /> Analyzing photos…
            </>
          ) : (
            <>
              <Sparkles size={15} /> Analyze photos
            </>
          )}
        </button>
        {!photoCount && (
          <p className="muted">
            Add progress photos for this date to request photo feedback.
          </p>
        )}
        {analyzing && (
          <p role="status">Reviewing your photos. This may take a moment.</p>
        )}
        {analysisError && (
          <p className="feedback-error" role="alert">
            {analysisError}
          </p>
        )}
        {review && (
          <div className="photo-assessment">
            {review.stale && (
              <p className="review-stale">
                Your photos or activity record have changed. Analyze photos
                again to update this review.
              </p>
            )}
            <p>{review.assessment?.summary}</p>
            <FeedbackList
              title="Visible observations"
              items={review.assessment?.observations}
            />
            <FeedbackList
              title="Suggested next steps"
              items={review.assessment?.next_steps}
            />
            <FeedbackList
              title="What these photos can tell us"
              items={review.assessment?.limitations}
            />
            <small>
              Reviewed{" "}
              {review.created
                ? labelDate(review.created.slice(0, 10), {
                    month: "short",
                    day: "numeric",
                    year: "numeric",
                  })
                : date}
              {review.model ? ` · ${review.model}` : ""}
            </small>
          </div>
        )}
      </div>
    </section>
  );
}
