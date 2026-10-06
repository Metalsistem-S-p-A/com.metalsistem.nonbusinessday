# MsNonBusinessDay

iDempiere plugin to manage non-business days and company closures from a full-screen, interactive calendar, and to shift document dates to the next business day.

It stores closures in the standard `C_NonBusinessDay` table (per organization and calendar), generates public holidays per country with [Jollyday](https://github.com/focus-shift/jollyday), and keeps the core payment-term logic working by materializing concrete dates.

## Features

- Full-screen calendar form (`WNonBusinessDayCalendar`) to view and manage non-business days per organization and per calendar, built on FullCalendar.
- Create closures by clicking a day or selecting a range; edit or deactivate existing ones from a contextual dialog.
- Soft delete via an "Active" flag: a closure you untick disappears from the calendar and is not recreated by a later generation.
- Yearly generation of public holidays for a chosen country (Jollyday), plus optional weekend closures, materialized into `C_NonBusinessDay`.
- Event handler that shifts invoice pay-schedule due dates and order promised dates to the next business day when the payment term has `IsNextBusinessDay` set.

## Screenshots

Calendar view:

![Calendar view](docs/calendar.png)

New / edit closure dialog:

![Closure dialog](docs/dialog.png)

## Requirements

- iDempiere 13 (Tycho / eclipse-plugin build).

## Usage

### The calendar screen

1. Open the form from its menu entry.
2. In the top toolbar, pick the **Organization** and the **Calendar**. Closures for the visible range are loaded automatically; moving month/year reloads only the dates in view.
3. Colors: blue events are national public holidays (rows carrying a country), orange events are company closures and weekends.
4. **Create**: click a day, or drag to select a range. The *New* dialog opens; enter a description, confirm the start/end dates and Save. One row per day in the range is created, skipping dates that already exist.
5. **Edit or remove**: click an existing event. The *Edit* dialog opens. Change the description or date, or untick **Active** and Save to deactivate it. Deactivation is a soft delete: the closure leaves the calendar and will not be recreated by a later generation. There is no hard delete from the form (remove a row from the standard window if you really need to).

### Generating a year

The **Generate year** button (bottom-right) launches the `GenerateNonBusinessDays` process. Parameters:

- `CalendarYear`: target year (defaults to next year).
- `AD_Org_ID`: organization scope.
- `C_Calendar_ID`: target calendar.
- `C_Country_ID`: country whose public holidays are generated (defaults to the client's default country). Its ISO code drives Jollyday.
- `LIT_YearsToKeep`: retention; older rows of that org are pruned.
- `OnMonday` .. `OnSunday`: which weekdays to materialize as closures (country-agnostic).

Public holidays are computed with Jollyday for the chosen country and materialized into `C_NonBusinessDay`, with the holiday name localized to the login language. Generation is idempotent and deduplicates by date, so a public holiday that falls on an already generated weekend does not create a duplicate row.

## Business-day date shifting

When a payment term has `IsNextBusinessDay` set, the event handler shifts, on document prepare/complete, the affected dates to the next business day, reading the closures from `C_NonBusinessDay` scoped by the document's organization. It currently covers:

- invoice payment-schedule due dates;
- order promised dates.

This coverage is not exhaustive: it is a starting point, and further document integrations are planned.