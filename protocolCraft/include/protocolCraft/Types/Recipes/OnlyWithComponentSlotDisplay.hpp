#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#pragma once

#include "protocolCraft/Types/Recipes/SlotDisplayData.hpp"
#include "protocolCraft/Types/Recipes/SlotDisplay.hpp"
#include "protocolCraft/Types/Components/DataComponents.hpp"

namespace ProtocolCraft
{
    class OnlyWithComponentSlotDisplay : public SlotDisplayData
    {
        SERIALIZED_FIELD(Source, SlotDisplay);
        SERIALIZED_FIELD(Component, Internal::DiffType<Components::DataComponentTypes, VarInt>);

        DECLARE_READ_WRITE_SERIALIZE;
    };
}
#endif
