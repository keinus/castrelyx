import { useCallback, useEffect, useState } from "react";
import { api, errorMessage } from "@/lib/api";
export function useResource<T = any>(path: string, interval?: number) {
  const [data, setData] = useState<T | null>(null),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision((v) => v + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setData(null);
    const load = async () => {
      try {
        const value = await api<T>(path, "GET", undefined, controller.signal);
        if (!controller.signal.aborted) {
          setData(value);
          setError("");
        }
      } catch (e) {
        if (!controller.signal.aborted) setError(errorMessage(e));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    };
    void load();
    const timer = interval ? setInterval(load, interval) : undefined;
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [path, revision, interval]);
  return { data, error, loading, refresh };
}
