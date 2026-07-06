#if PROTOCOL_VERSION > 769 /* > 1.21.4 */
#pragma once
#include "protocolCraft/Types/Components/DataComponentType.hpp"
#include "protocolCraft/Types/Identifier.hpp"
#include "protocolCraft/Types/Holder.hpp"
#include "protocolCraft/Types/Components/Subtypes/DamageReduction.hpp"
#include "protocolCraft/Types/Components/Subtypes/ItemDamageFunction.hpp"
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#include "protocolCraft/Types/HolderSet.hpp"
#endif

#include <optional>

namespace ProtocolCraft
{
    namespace Components
    {
        class DataComponentTypeBlocksAttacks : public DataComponentType
        {
            SERIALIZED_FIELD(BlockDelaySeconds, float);
            SERIALIZED_FIELD(DisableCooldownScale, float);
            SERIALIZED_FIELD(DamageReductions, std::vector<DamageReduction>);
            SERIALIZED_FIELD(ItemDamage, ItemDamageFunction);
#if PROTOCOL_VERSION < 775 /* < 26.1 */
            SERIALIZED_FIELD(BypassedBy, std::optional<Identifier>);
#else
            SERIALIZED_FIELD(BypassedBy, std::optional<HolderSet>);
#endif
            SERIALIZED_FIELD(BlockSound, std::optional<Holder<SoundEvent>>);
            SERIALIZED_FIELD(DisableSound, std::optional<Holder<SoundEvent>>);

            DECLARE_READ_WRITE_SERIALIZE;
        };
    }
}
#endif
