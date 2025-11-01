// Main app entry point - load all dependencies
import htmx from 'htmx.org';
import _hyperscript from 'hyperscript.org';
import { computePosition, flip, shift, offset } from '@floating-ui/dom';

// Expose libraries globally
window.htmx = htmx;
window._hyperscript = _hyperscript;

// Initialize hyperscript
_hyperscript.browserInit();

console.log('Bundle loaded!', { htmx: window.htmx, hyperscript: window._hyperscript });

// Tooltip positioning with Floating UI
window.positionTooltip = async function(triggerElement) {
  // Store tooltip reference on the trigger element if we haven't already
  let tooltip = triggerElement._tooltip;

  if (!tooltip) {
    // Look for tooltip as a sibling in the parent container
    tooltip = triggerElement.parentElement.querySelector('.tooltip');
    if (!tooltip) {
      console.warn('No tooltip found in', triggerElement.parentElement);
      return;
    }

    // Store reference and move to body once
    triggerElement._tooltip = tooltip;

    // Move tooltip to body for proper fixed positioning
    if (tooltip.parentElement !== document.body) {
      document.body.appendChild(tooltip);
    }
  }

  // Update position every time (trigger might have moved)
  console.log("trigger-element", triggerElement)
  const { x, y } = await computePosition(triggerElement, tooltip, {
    placement: 'top',
    middleware: [
      offset(8),
      flip(),
      shift({ padding: 8 })
    ]
  });

  Object.assign(tooltip.style, {
    left: `${x}px`,
    top: `${y}px`,
  });
};

console.log('positionTooltip function registered');
