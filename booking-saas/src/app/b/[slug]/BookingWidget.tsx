"use client";

import { useActionState, useEffect, useState } from "react";
import { createPublicBooking } from "@/app/actions/bookings";
import { Button, Card, ErrorText, Input, Label } from "@/components/ui";

interface ServiceOption {
  id: string;
  name: string;
  durationMin: number;
  priceCents: number;
}

interface SlotOption {
  startUtc: string;
  startLocalLabel: string;
}

function price(cents: number): string {
  return cents === 0 ? "Free" : `$${(cents / 100).toFixed(cents % 100 === 0 ? 0 : 2)}`;
}

function todayISO(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function BookingWidget({
  slug,
  services,
}: {
  slug: string;
  services: ServiceOption[];
}) {
  const [serviceId, setServiceId] = useState<string>(services[0]?.id ?? "");
  const [date, setDate] = useState<string>(todayISO());
  const [slots, setSlots] = useState<SlotOption[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<SlotOption | null>(null);
  const [state, formAction, pending] = useActionState(createPublicBooking, {});

  useEffect(() => {
    if (!serviceId || !date) return;
    let cancelled = false;
    setLoading(true);
    setSelected(null);
    fetch(
      `/api/slots?slug=${encodeURIComponent(slug)}&serviceId=${encodeURIComponent(serviceId)}&date=${date}`,
    )
      .then((r) => (r.ok ? r.json() : { slots: [] }))
      .then((data) => {
        if (!cancelled) setSlots(data.slots ?? []);
      })
      .catch(() => {
        if (!cancelled) setSlots([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [slug, serviceId, date]);

  return (
    <div className="space-y-6">
      <section>
        <h2 className="mb-3 text-lg font-semibold">1. Choose a service</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {services.map((s) => (
            <button
              key={s.id}
              type="button"
              onClick={() => setServiceId(s.id)}
              data-testid={`service-${s.name}`}
              className={`rounded-xl border p-4 text-left transition-colors ${
                serviceId === s.id
                  ? "border-indigo-600 bg-indigo-50"
                  : "border-slate-200 bg-white hover:border-slate-300"
              }`}
            >
              <p className="font-medium">{s.name}</p>
              <p className="text-sm text-slate-500">
                {s.durationMin} min · {price(s.priceCents)}
              </p>
            </button>
          ))}
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-lg font-semibold">2. Pick a date &amp; time</h2>
        <div className="mb-4 max-w-xs">
          <Label htmlFor="date">Date</Label>
          <Input
            id="date"
            type="date"
            value={date}
            min={todayISO()}
            onChange={(e) => setDate(e.target.value)}
          />
        </div>
        {loading ? (
          <p className="text-sm text-slate-500">Loading times…</p>
        ) : slots && slots.length > 0 ? (
          <div className="flex flex-wrap gap-2" data-testid="slot-list">
            {slots.map((slot) => (
              <button
                key={slot.startUtc}
                type="button"
                onClick={() => setSelected(slot)}
                className={`rounded-lg border px-3 py-1.5 text-sm transition-colors ${
                  selected?.startUtc === slot.startUtc
                    ? "border-indigo-600 bg-indigo-600 text-white"
                    : "border-slate-300 bg-white hover:border-indigo-400"
                }`}
              >
                {slot.startLocalLabel}
              </button>
            ))}
          </div>
        ) : (
          <p className="text-sm text-slate-500" data-testid="no-slots">
            No available times on this date. Try another day.
          </p>
        )}
      </section>

      {selected && (
        <Card>
          <h2 className="mb-3 text-lg font-semibold">3. Your details</h2>
          <p className="mb-4 text-sm text-slate-600">
            Booking <strong>{services.find((s) => s.id === serviceId)?.name}</strong>{" "}
            on <strong>{date}</strong> at{" "}
            <strong>{selected.startLocalLabel}</strong>
          </p>
          <form action={formAction} className="space-y-4">
            <input type="hidden" name="slug" value={slug} />
            <input type="hidden" name="serviceId" value={serviceId} />
            <input type="hidden" name="startUtc" value={selected.startUtc} />
            <div>
              <Label htmlFor="customerName">Name</Label>
              <Input id="customerName" name="customerName" required />
            </div>
            <div>
              <Label htmlFor="customerEmail">Email</Label>
              <Input id="customerEmail" name="customerEmail" type="email" required />
            </div>
            <div>
              <Label htmlFor="customerPhone">Phone (optional)</Label>
              <Input id="customerPhone" name="customerPhone" type="tel" />
            </div>
            <ErrorText>{state.error}</ErrorText>
            <Button type="submit" disabled={pending} className="w-full">
              {pending ? "Booking…" : "Confirm booking"}
            </Button>
          </form>
        </Card>
      )}
    </div>
  );
}
