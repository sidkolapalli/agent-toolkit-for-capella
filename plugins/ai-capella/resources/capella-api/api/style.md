# api.style.setColor()

Control the visual appearance of elements on Sirius diagrams.

## Signatures

```groovy
// Set general color (label + border)
boolean setColor(DRepresentation diagram, EObject element, String color)

// Set background color
boolean setBackgroundColor(DRepresentation diagram, EObject element, String color)

// Set label color
boolean setLabelColor(DRepresentation diagram, EObject element, String color)

// Show/Hide
boolean hide(DRepresentation diagram, EObject element)
boolean show(DRepresentation diagram, EObject element)
```

## Colors

You can use names or hex codes:
- Names: `red`, `green`, `blue`, `orange`, `purple`, `black`, `white`, `gray`
- Hex: `#FF0000`, `#00FF00`, `#CCCCFF`

## Examples

```groovy
def diag = api.diagram.lastOpened()
def comp = api.find("Flight Computer")

// Make it RED (alert)
api.style.setColor(diag, comp, "red")

// Make it subtle (gray background)
api.style.setBackgroundColor(diag, comp, "#E0E0E0")

// Hide unnecessary elements
api.style.hide(diag, api.find("Old Legacy Block"))
```

## Tips

- Style changes require a diagram refresh (handled automatically).
- If an element doesn't support a specific style (e.g., ports might not have a background), the setting is ignored safely.
