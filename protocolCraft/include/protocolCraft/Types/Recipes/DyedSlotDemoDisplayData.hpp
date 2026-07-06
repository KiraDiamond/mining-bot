#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/Types/Recipes/SlotDisplayData.hpp"
#include "protocolCraft/Types/Recipes/SlotDisplay.hpp"

namespace ProtocolCraft
{
    class DyedSlotDemoDisplayData : public SlotDisplayData
    {
        SERIALIZED_FIELD(Dye, SlotDisplay);
        SERIALIZED_FIELD(Target, SlotDisplay);

        DECLARE_READ_WRITE_SERIALIZE;
    };
}
#endif
