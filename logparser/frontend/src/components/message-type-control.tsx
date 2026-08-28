import { useId, useState } from "react";
import { Plus } from "lucide-react";
import { useWorkspace } from "@/lib/workspace";
import { SelectInput } from "./shared";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Field, FieldLabel } from "./ui/field";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
  DialogTrigger,
} from "./ui/dialog";
export function MessageTypeControl() {
  const { messageType, messageTypes, changeType } = useWorkspace(),
    [open, setOpen] = useState(false),
    [value, setValue] = useState(""),
    id = useId();
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-xs text-muted-foreground">Message type</span>
      <div className="w-48 max-w-full">
        <SelectInput
          label="Message type"
          value={messageType}
          options={messageTypes}
          onChange={changeType}
        />
      </div>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogTrigger asChild>
          <Button variant="outline" size="icon" aria-label="New message type">
            <Plus />
          </Button>
        </DialogTrigger>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>New message type</DialogTitle>
            <DialogDescription>
              A message type connects inputs, processing steps, and outputs. It
              is persisted when you save its first component.
            </DialogDescription>
          </DialogHeader>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (value.trim()) {
                changeType(value.trim());
                setOpen(false);
                setValue("");
              }
            }}
          >
            <Field>
              <FieldLabel htmlFor={id}>Message type name</FieldLabel>
              <Input
                id={id}
                autoFocus
                value={value}
                onChange={(e) => setValue(e.target.value)}
                required
              />
            </Field>
            <DialogFooter className="mt-5">
              <Button disabled={!value.trim()}>Create workspace</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
