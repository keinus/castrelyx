import { useEffect, useState } from "react";
import { RefreshCw, RotateCcw, Save } from "lucide-react";
import { toast } from "sonner";
import { api, errorMessage } from "@/lib/api";
import { useDirty } from "@/lib/workspace";
import { useResource } from "@/hooks/use-resource";
import { PageHeading, ErrorNotice, ConfirmAction } from "@/components/shared";
import { AttributeTree } from "@/components/adapters/collection-fields";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
export function Settings() {
  const settings = useResource<Record<string, any>>("/settings"),
    [draft, setDraft] = useState<Record<string, any>>({}),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    if (settings.data) setDraft(settings.data);
  }, [settings.data]);
  const dirty =
    !!settings.data && JSON.stringify(draft) !== JSON.stringify(settings.data);
  useDirty(dirty);
  return (
    <>
      <PageHeading
        title="Settings"
        description="Shared runtime settings and pipeline maintenance."
      />
      {settings.error && (
        <ErrorNotice error={settings.error} retry={settings.refresh} />
      )}
      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <Card>
          <CardHeader>
            <CardTitle>Runtime configuration</CardTitle>
            <CardDescription>
              These settings are supplied by the server. Changes apply to the
              current configuration.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {settings.loading ? (
              <Skeleton className="h-52" />
            ) : (
              <fieldset
                disabled={busy || !!settings.error}
                className="grid gap-5 sm:grid-cols-2"
              >
                {Object.entries(draft)
                  .filter(([key]) => !key.startsWith("castrelyx.seed."))
                  .map(([key, value]) => (
                    <AttributeTree
                      key={key}
                      label={key.replaceAll("_", " ")}
                      value={value}
                      onChange={(next) =>
                        setDraft((previous) => ({ ...previous, [key]: next }))
                      }
                    />
                  ))}
              </fieldset>
            )}
            {error && <ErrorNotice error={error} />}
          </CardContent>
          <CardFooter className="justify-end gap-2">
            <Button
              variant="outline"
              disabled={!dirty || busy}
              onClick={() => setDraft(settings.data || {})}
            >
              Discard
            </Button>
            <Button
              disabled={!dirty || busy}
              onClick={async () => {
                setBusy(true);
                setError("");
                try {
                  if (
                    ["parser_threads", "flush_interval"].some(
                      (key) =>
                        key in draft &&
                        (!Number.isInteger(draft[key]) || draft[key] < 1),
                    )
                  )
                    throw new Error(
                      "Parser threads and flush interval must be positive whole numbers.",
                    );
                  await api(
                    "/settings",
                    "PUT",
                    Object.fromEntries(
                      Object.entries(draft).filter(
                        ([key, value]) =>
                          value !== settings.data?.[key] &&
                          !key.startsWith("castrelyx.seed."),
                      ),
                    ),
                  );
                  settings.refresh();
                  toast.success("Settings saved");
                } catch (e) {
                  setError(errorMessage(e));
                } finally {
                  setBusy(false);
                }
              }}
            >
              <Save data-icon="inline-start" />
              Save settings
            </Button>
          </CardFooter>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Pipeline maintenance</CardTitle>
            <CardDescription>
              These actions affect the running pipeline and may briefly
              interrupt processing.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {[
              {
                path: "validate-and-reload",
                label: "Validate & reload",
                Icon: RefreshCw,
              },
              {
                path: "reload",
                label: "Reload configuration",
                Icon: RefreshCw,
              },
              { path: "restart", label: "Restart pipeline", Icon: RotateCcw },
            ].map(({ path, label, Icon }) => (
              <ConfirmAction
                key={path}
                title={`${label}?`}
                description="Saved configuration will be used. Unsaved changes on this page are not included. Active adapters may be restarted."
                label={label}
                trigger={
                  <Button variant="outline">
                    <Icon data-icon="inline-start" />
                    {label}
                  </Button>
                }
                action={async () => {
                  await api(`/pipeline/${path}`, "POST");
                  toast.success(`${label} completed`);
                }}
              />
            ))}
          </CardContent>
        </Card>
      </div>
    </>
  );
}
