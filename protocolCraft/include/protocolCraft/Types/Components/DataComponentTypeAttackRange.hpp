#if PROTOCOL_VERSION > 773 /* > 1.21.10 */
#pragma once
#include "protocolCraft/Types/Components/DataComponentType.hpp"

namespace ProtocolCraft
{
    namespace Components
    {
        class DataComponentTypeAttackRange : public DataComponentType
        {
#if PROTOCOL_VERSION < 775 /* < 26.1 */
            SERIALIZED_FIELD(MinRange, float);
            SERIALIZED_FIELD(MaxRange, float);
            SERIALIZED_FIELD(MinCreativeRange, float);
            SERIALIZED_FIELD(MaxCreativeRange, float);
#else
            SERIALIZED_FIELD(MinReach, float);
            SERIALIZED_FIELD(MaxReach, float);
            SERIALIZED_FIELD(MinCreativeReach, float);
            SERIALIZED_FIELD(MaxCreativeReach, float);
#endif
            SERIALIZED_FIELD(HitboxMargin, float);
            SERIALIZED_FIELD(MobFactor, float);

            DECLARE_READ_WRITE_SERIALIZE;
        };
    }
}
#endif
