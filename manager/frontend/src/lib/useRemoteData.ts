import { useCallback, useEffect, useRef, useState } from 'react';

/** Keep the last successful response, and ignore superseded or unmounted requests. */
export function useRemoteData<T>(fetcher: () => Promise<T>, failureMessage: string) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const sequence = useRef(0);
  const reload = useCallback(async () => {
    const request = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const result = await fetcher();
      if (request === sequence.current) setData(result);
    } catch (cause) {
      if (request === sequence.current) setError(`${failureMessage}${cause instanceof Error ? ` ${cause.message}` : ''}`);
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }, [fetcher, failureMessage]);
  useEffect(() => {
    void reload();
    return () => { sequence.current++; };
  }, [reload]);
  return { data, loading, error, reload };
}
