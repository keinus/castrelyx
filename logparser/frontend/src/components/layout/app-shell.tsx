import type { CSSProperties, ReactNode } from "react";
import {
  Activity,
  BookOpen,
  Braces,
  Cable,
  ChevronRight,
  GitBranch,
  Layers2,
  ListFilter,
  Radio,
  Settings2,
  SquareTerminal,
  Workflow,
} from "lucide-react";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarGroupContent,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuItem,
  SidebarMenuButton,
  SidebarProvider,
  SidebarRail,
  SidebarTrigger,
  useSidebar,
} from "@/components/ui/sidebar";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import { useWorkspace, viewLabels, type View } from "@/lib/workspace";

const groups = [
  {
    name: "Workspace",
    items: [
      ["overview", Activity],
      ["studio", Workflow],
      ["live", SquareTerminal],
    ],
  },
  {
    name: "Configuration",
    items: [
      ["inputs", Radio],
      ["parsers", Braces],
      ["transforms", ListFilter],
      ["mapping", GitBranch],
      ["outputs", Cable],
    ],
  },
  {
    name: "System",
    items: [
      ["settings", Settings2],
      ["docs", BookOpen],
    ],
  },
] as const;
function AppSidebar() {
  const { view, navigate, data } = useWorkspace(),
    { setOpenMobile, isMobile } = useSidebar();
  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="h-16 justify-center border-b px-4">
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              size="lg"
              onClick={() => {
                navigate("overview");
                if (isMobile) setOpenMobile(false);
              }}
              tooltip="LogParser overview"
            >
              <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
                <Layers2 className="size-5" />
              </div>
              <span className="text-base font-semibold tracking-tight">
                LogParser
              </span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent className="py-4">
        {groups.map((group) => (
          <SidebarGroup key={group.name}>
            <SidebarGroupLabel className="mb-1 text-[10px] font-semibold uppercase tracking-[0.13em]">
              {group.name}
            </SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map(([key, Icon]) => (
                  <SidebarMenuItem key={key}>
                    <SidebarMenuButton
                      asChild
                      isActive={view === key}
                      tooltip={viewLabels[key]}
                    >
                      <a
                        href={`#${key}`}
                        onClick={(e) => {
                          e.preventDefault();
                          navigate(key as View);
                          if (isMobile) setOpenMobile(false);
                        }}
                        aria-current={view === key ? "page" : undefined}
                      >
                        <Icon />
                        <span>{viewLabels[key]}</span>
                        {key === "inputs" && (
                          <span className="ml-auto text-xs text-muted-foreground">
                            {data.input.length}
                          </span>
                        )}
                      </a>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter className="border-t p-4">
        <div className="flex items-center gap-2 text-xs text-muted-foreground group-data-[collapsible=icon]:hidden">
          <span className="size-1.5 rounded-full bg-primary" />
          Pipeline console<span className="ml-auto font-mono">0.3.1</span>
        </div>
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}
export function AppShell({ children }: { children: ReactNode }) {
  const { view, status } = useWorkspace();
  const group = ["overview", "studio", "live"].includes(view)
    ? "Workspace"
    : ["settings", "docs"].includes(view)
      ? "System"
      : "Configuration";
  return (
    <SidebarProvider style={{ "--sidebar-width": "14.5rem" } as CSSProperties}>
      <a
        href="#main-content"
        className="skip-link"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById("main-content")?.focus();
        }}
      >
        Skip to content
      </a>
      <AppSidebar />
      <SidebarInset className="min-w-0">
        <header className="sticky top-0 z-30 flex h-16 shrink-0 items-center gap-3 border-b bg-background/95 px-4 backdrop-blur-sm md:px-7">
          <SidebarTrigger />
          <Separator orientation="vertical" className="h-4" />
          <nav
            aria-label="Breadcrumb"
            className="flex min-w-0 items-center gap-2 text-sm"
          >
            <span className="hidden text-muted-foreground sm:inline">
              {group}
            </span>
            <ChevronRight className="hidden size-3.5 text-muted-foreground sm:block" />
            <span className="truncate">{viewLabels[view]}</span>
          </nav>
          <Badge variant="outline" className="ml-auto shrink-0 gap-2">
            <span
              className={`size-1.5 rounded-full ${status?.status === "RUNNING" ? "bg-success" : "bg-muted-foreground"}`}
            />
            <span className="hidden sm:inline">Pipeline </span>
            {status ? String(status.status).toLowerCase() : "unavailable"}
          </Badge>
        </header>
        <div
          id="main-content"
          className="mx-auto flex w-full max-w-[1800px] flex-col gap-4 p-4 md:p-7"
          tabIndex={-1}
        >
          {children}
        </div>
      </SidebarInset>
    </SidebarProvider>
  );
}
