import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { api, errorMessage, loadInventory } from "./api";
import { emptyInventory, type Inventory } from "./types";

export type View =
  | "overview"
  | "studio"
  | "live"
  | "inputs"
  | "parsers"
  | "transforms"
  | "mapping"
  | "outputs"
  | "settings"
  | "docs";
export const viewLabels: Record<View, string> = {
  overview: "Overview",
  studio: "Pipeline Studio",
  live: "Live Tail",
  inputs: "Inputs",
  parsers: "Parsers",
  transforms: "Transforms",
  mapping: "Schema Mapping",
  outputs: "Outputs",
  settings: "Settings",
  docs: "Documentation",
};
function route() {
  const [path, query] = location.hash.slice(1).split("?");
  const aliases: Record<string, View> = {
    "pipeline-studio": "studio",
    sources: "inputs",
    sinks: "outputs",
    "schema-map": "mapping",
    "live-tail": "live",
    "pipeline-view": "overview",
  };
  return {
    view: (path in viewLabels ? path : aliases[path] || "studio") as View,
    messageType: new URLSearchParams(query).get("type") || "",
  };
}
interface Workspace {
  view: View;
  messageType: string;
  messageTypes: string[];
  data: Inventory;
  loading: boolean;
  error: string | null;
  status: any;
  refresh: () => Promise<void>;
  navigate: (view: View, type?: string) => void;
  changeType: (type: string) => void;
  guard: () => boolean;
  setDirty: (value: boolean) => void;
}
const Context = createContext<Workspace | null>(null);
export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const [current, setCurrent] = useState(route),
    [data, setData] = useState(emptyInventory),
    [loading, setLoading] = useState(true),
    [error, setError] = useState<string | null>(null),
    [status, setStatus] = useState<any>(null);
  const dirty = useRef(false),
    currentRef = useRef(current);
  const setDirty = useCallback((value: boolean) => {
    dirty.current = value;
  }, []);
  const guard = useCallback(
    () => !dirty.current || window.confirm("Discard unsaved changes?"),
    [],
  );
  const messageTypes = [
    ...new Set(
      [
        ...Object.entries(data)
          .flatMap(([stage, items]) =>
            stage === "output"
              ? items.filter((a) => a.messagetype && a.messagetype !== "all")
              : items,
          )
          .map((a) => a.messagetype)
          .filter(Boolean),
        current.messageType,
      ].filter(Boolean),
    ),
  ].sort();
  const refresh = useCallback(async () => {
    setError(null);
    try {
      setData(await loadInventory());
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  useEffect(() => {
    let active = true;
    const poll = async () => {
      try {
        const value = await api("/pipeline/status");
        if (active) setStatus(value);
      } catch {
        if (active) setStatus(null);
      }
    };
    void poll();
    const interval = setInterval(poll, 10000);
    return () => {
      active = false;
      clearInterval(interval);
    };
  }, []);
  useEffect(() => {
    currentRef.current = current;
  }, [current]);
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" });
  }, [current.view, current.messageType]);
  useEffect(() => {
    const onHash = () => {
      if (guard()) {
        dirty.current = false;
        setCurrent(route());
      } else
        history.replaceState(
          null,
          "",
          `#${currentRef.current.view}?type=${encodeURIComponent(currentRef.current.messageType)}`,
        );
    };
    const onUnload = (e: BeforeUnloadEvent) => {
      if (dirty.current) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    addEventListener("hashchange", onHash);
    addEventListener("beforeunload", onUnload);
    return () => {
      removeEventListener("hashchange", onHash);
      removeEventListener("beforeunload", onUnload);
    };
  }, [guard]);
  const selectedType = current.messageType || messageTypes[0] || "";
  const navigate = (view: View, type = selectedType) => {
    if (!guard()) return;
    dirty.current = false;
    const next = { view, messageType: type };
    history.pushState(
      null,
      "",
      `#${view}${type ? `?type=${encodeURIComponent(type)}` : ""}`,
    );
    currentRef.current = next;
    setCurrent(next);
  };
  return (
    <Context.Provider
      value={{
        ...current,
        messageType: selectedType,
        messageTypes,
        data,
        loading,
        error,
        status,
        refresh,
        navigate,
        changeType: (type) => navigate(current.view, type),
        guard,
        setDirty,
      }}
    >
      {children}
    </Context.Provider>
  );
}
export function useWorkspace() {
  const value = useContext(Context);
  if (!value) throw new Error("Workspace provider missing");
  return value;
}
export function useDirty(value: boolean) {
  const { setDirty } = useWorkspace();
  useEffect(() => {
    setDirty(value);
    return () => setDirty(false);
  }, [value, setDirty]);
}
