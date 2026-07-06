#if PROTOCOL_VERSION > 767 /* > 1.21.1 */
#pragma once

#include "protocolCraft/NetworkType.hpp"

#include <memory>

namespace ProtocolCraft
{
    enum class SlotDisplaysDataType
    {
        None = -1,
        Empty,
        AnyFuel,
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        WithAnyPotion,
        OnlyWithComponent,
#endif
        Item,
        ItemStack,
        Tag,
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        DyedSlotDemo,
#endif
        SmithingTrim,
        WithRemainder,
        Composite,
        NUM_SLOT_DISPLAYS_DATA_TYPES
    };

    class SlotDisplayData;

    class SlotDisplay : public NetworkType
    {
        SERIALIZED_FIELD_WITHOUT_GETTER_SETTER(Type, Internal::DiffType<SlotDisplaysDataType, VarInt>);
        SERIALIZED_FIELD_WITHOUT_GETTER_SETTER(Data, std::shared_ptr<SlotDisplayData>);

        DECLARE_READ_WRITE_SERIALIZE;

        GETTER(Type);
        GETTER(Data);

    public:
        SlotDisplay& SetType(const SlotDisplaysDataType type);
    };
}
#endif
