import type { Adapter, Inventory, Stage } from "./types";
import { stages } from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T = any>(
  path: string,
  method = "GET",
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    method,
    signal,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let data: any;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!response.ok)
    throw new ApiError(
      typeof data === "string"
        ? data
        : data?.message || data?.error || `Request failed (${response.status})`,
      response.status,
    );
  return data as T;
}
export const endpoints: Record<Stage, string> = {
  input: "/input-adapters",
  parser: "/parsers",
  transform: "/transforms",
  output: "/output-adapters",
};
async function all(stage: Stage): Promise<Adapter[]> {
  const items: Adapter[] = [];
  for (let page = 0; ; page++) {
    const result = await api<
      Adapter[] | { content: Adapter[]; last: boolean; totalPages: number }
    >(`${endpoints[stage]}?page=${page}&size=100`);
    if (Array.isArray(result)) return result;
    items.push(...result.content);
    if (
      result.last ||
      page + 1 >= result.totalPages ||
      result.content.length < 100
    )
      return items;
  }
}
export async function loadInventory(): Promise<Inventory> {
  const values = await Promise.all(stages.map(all));
  return Object.fromEntries(
    stages.map((key, i) => [key, values[i]]),
  ) as Inventory;
}
export const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : String(error);
