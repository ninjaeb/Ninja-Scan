"use client";

import { useActionState } from "react";
import { createService } from "@/app/actions/services";
import { Button, ErrorText, Input, Label } from "@/components/ui";

export function ServiceForm() {
  const [state, formAction, pending] = useActionState(createService, {});

  return (
    <form action={formAction} className="space-y-4">
      <div>
        <Label htmlFor="svc-name">Name</Label>
        <Input id="svc-name" name="name" required placeholder="Haircut" />
      </div>
      <div className="grid grid-cols-3 gap-4">
        <div>
          <Label htmlFor="svc-duration">Duration (min)</Label>
          <Input
            id="svc-duration"
            name="durationMin"
            type="number"
            min={5}
            step={5}
            defaultValue={30}
            required
          />
        </div>
        <div>
          <Label htmlFor="svc-price">Price ($)</Label>
          <Input
            id="svc-price"
            name="priceDollars"
            type="number"
            min={0}
            step="0.01"
            defaultValue={0}
            required
          />
        </div>
        <div>
          <Label htmlFor="svc-buffer">Buffer after (min)</Label>
          <Input
            id="svc-buffer"
            name="bufferMin"
            type="number"
            min={0}
            step={5}
            defaultValue={0}
          />
        </div>
      </div>
      <ErrorText>{state.error}</ErrorText>
      <Button type="submit" disabled={pending}>
        {pending ? "Adding…" : "Add service"}
      </Button>
    </form>
  );
}
