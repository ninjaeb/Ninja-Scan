"use client";

import { useActionState } from "react";
import {
  Button,
  ErrorText,
  Input,
  Label,
  Select,
  Textarea,
} from "@/components/ui";
import type { FormState } from "@/app/actions/auth";

export function BusinessForm({
  action,
  timezones,
  submitLabel,
  initial,
}: {
  action: (prev: FormState, formData: FormData) => Promise<FormState>;
  timezones: string[];
  submitLabel: string;
  initial?: {
    name: string;
    slug: string;
    timezone: string;
    description: string;
  };
}) {
  const [state, formAction, pending] = useActionState(action, {});

  return (
    <form action={formAction} className="space-y-4">
      <div>
        <Label htmlFor="name">Business name</Label>
        <Input
          id="name"
          name="name"
          required
          defaultValue={initial?.name}
          placeholder="Acme Cuts"
        />
      </div>
      <div>
        <Label htmlFor="slug">Booking page URL</Label>
        <div className="flex items-center gap-2">
          <span className="text-sm text-slate-500">/b/</span>
          <Input
            id="slug"
            name="slug"
            required
            defaultValue={initial?.slug}
            placeholder="acme-cuts"
            pattern="[a-z0-9]+(-[a-z0-9]+)*"
            title="Lowercase letters, numbers and dashes"
          />
        </div>
      </div>
      <div>
        <Label htmlFor="timezone">Timezone</Label>
        <Select
          id="timezone"
          name="timezone"
          required
          defaultValue={initial?.timezone ?? "America/New_York"}
        >
          {timezones.map((tz) => (
            <option key={tz} value={tz}>
              {tz}
            </option>
          ))}
        </Select>
      </div>
      <div>
        <Label htmlFor="description">Description (shown on your booking page)</Label>
        <Textarea
          id="description"
          name="description"
          rows={3}
          defaultValue={initial?.description}
          placeholder="Tell customers what you offer"
        />
      </div>
      <ErrorText>{state.error}</ErrorText>
      <Button type="submit" disabled={pending}>
        {pending ? "Saving…" : submitLabel}
      </Button>
    </form>
  );
}
